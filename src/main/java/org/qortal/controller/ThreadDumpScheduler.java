package org.qortal.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.settings.Settings;
import org.qortal.utils.FilesystemUtils;
import org.qortal.utils.NTP;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ThreadDumpScheduler {

    private static final Logger LOGGER = LogManager.getLogger(ThreadDumpScheduler.class);

    private static final String THREAD_DUMP_FOLDER = "thread-dumps";
    private static final String TIMESTAMP_FORMAT = "yyyy-MM-dd_HH-mm-ss";

    private static final long STALE_FILE_TIMEOUT = Settings.getInstance().getThreadDumpExpiration() * 60*60*1000L;

    private ScheduledExecutorService scheduler;
    private File threadDumpDir;

    private Map<Long, Long> cpuTimeById = new HashMap<>();

    private static ThreadDumpScheduler singleton;

    public static ThreadDumpScheduler getInstance() {

        if( singleton == null ) {
            singleton = new ThreadDumpScheduler();
        }

        return singleton;
    }

    private ThreadDumpScheduler() {
    }

    public void start() {
        long intervalMinutes = Settings.getInstance().getThreadDumpInterval();

        if( intervalMinutes > 0 ) {
            // Create the scheduler
            this.scheduler = Executors.newSingleThreadScheduledExecutor();

            // Get the installation directory (assuming it's the user.dir)
            String installDir = System.getProperty("user.dir");
            this.threadDumpDir = new File(installDir, THREAD_DUMP_FOLDER);

            // Create the directory if it doesn't exist
            if (!threadDumpDir.exists()) {
                threadDumpDir.mkdirs();
            }

            // Schedule the thread dump task
            scheduler.scheduleAtFixedRate(
                    this::generateThreadDump,
                    intervalMinutes,
                    intervalMinutes,
                    TimeUnit.MINUTES
            );

            LOGGER.info("Thread dump scheduler started. Dumps will be written to: {} every {} minutes", threadDumpDir.getAbsolutePath(), intervalMinutes);
        }
    }

    private void generateThreadDump() {
        try {
            Long now = NTP.getTime();
            if (now == null) {
                return;
            }

            FilesystemUtils.deleteFilesByTime(threadDumpDir.toPath(), now, STALE_FILE_TIMEOUT);

            // Create timestamp for the filename
            SimpleDateFormat dateFormat = new SimpleDateFormat(TIMESTAMP_FORMAT);
            String timestamp = dateFormat.format(new Date());
            String fileName = "thread-dump-" + timestamp + ".txt";
            File threadDumpFile = new File(threadDumpDir, fileName);
            
            // Get the thread dump
            ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
            ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);

            // Write to file
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(threadDumpFile))) {
                // Write header with timestamp
                writer.write("Thread dump generated at: " + new Date().toString());
                writer.newLine();
                writer.newLine();

                writer.write("Max Memory: " + Runtime.getRuntime().maxMemory()/1000_000 + " MB");
                writer.newLine();

                writer.write("Total Memory: " + Runtime.getRuntime().totalMemory()/1000_000 + " MB");
                writer.newLine();

                writer.write("Memory In Use: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/1000_000 + " MB");
                writer.newLine();

                writer.write("Free Memory: " + Runtime.getRuntime().freeMemory()/1000_000 + " MB");
                writer.newLine();
                writer.newLine();

                writer.write("Available Processors: " + Runtime.getRuntime().availableProcessors());
                writer.newLine();
                writer.newLine();

                Map<Long, Long> cpuTimeLapsedById = new HashMap<>(threadInfos.length);
                Map<Long, ThreadInfo> infoById = new HashMap<>(threadInfos.length);

                // Write each thread's information
                for (ThreadInfo threadInfo : threadInfos) {
                    long cpuTime = threadMXBean.getThreadCpuTime(threadInfo.getThreadId());
                    long cpuTimeLapsed = cpuTime - this.cpuTimeById.getOrDefault(threadInfo.getThreadId(), 0L);
                    this.cpuTimeById.put(threadInfo.getThreadId(), cpuTime);
                    cpuTimeLapsedById.put(threadInfo.getThreadId(), cpuTimeLapsed);
                    infoById.put(threadInfo.getThreadId(), threadInfo);
                }

                List<Long> threadIds
                    = cpuTimeLapsedById.entrySet().stream()
                        .sorted(Comparator.comparingLong(Map.Entry::getValue))
                        .map(Map.Entry::getKey)
                        .collect(Collectors.toList());
                Collections.reverse(threadIds);

                long interval = Settings.getInstance().getThreadDumpInterval() * 60;

                for( Long threadId : threadIds ) {
                    ThreadInfo threadInfo = infoById.get(threadId);
                    long cpuTimeLapsed = cpuTimeLapsedById.get(threadId) / 1_000_000_000;
                    long percentage = (100 * cpuTimeLapsed) / interval;

                    writer.write(String.valueOf(percentage));
                    writer.write( "% CPU Usage Time Relative To Total Time" );
                    writer.newLine();
                    writer.write(threadInfo.toString());
                    writer.newLine();
                    writer.newLine();
                }
            }
            
            LOGGER.info("Thread dump generated: {}", threadDumpFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    /**
     * Writes a one-off full thread dump immediately to the thread-dumps folder, with the given
     * filename prefix. Best-effort: never throws, and does not depend on NTP (which may be
     * unavailable during shutdown). Used by the Controller shutdown watchdog to capture a stalled
     * shutdown — the periodic scheduler is stopped early in shutdown, so it can't record the hang.
     * <p>
     * No-op unless the thread-dump feature is enabled ({@code threadDumpInterval > 0}). When
     * disabled, the {@code thread-dumps/} folder is not guaranteed to exist and the operator hasn't
     * opted in, so we neither create the folder nor write a dump.
     */
    public void dumpNow(String prefix) {
        if (Settings.getInstance().getThreadDumpInterval() <= 0) {
            return;
        }
        try {
            String installDir = System.getProperty("user.dir");
            File dir = new File(installDir, THREAD_DUMP_FOLDER);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            SimpleDateFormat dateFormat = new SimpleDateFormat(TIMESTAMP_FORMAT);
            String timestamp = dateFormat.format(new Date());
            File file = new File(dir, prefix + "-" + timestamp + ".txt");

            ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
            ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                writer.write("Thread dump generated at: " + new Date());
                writer.newLine();
                writer.write("Reason: " + prefix);
                writer.newLine();
                writer.newLine();
                for (ThreadInfo threadInfo : threadInfos) {
                    // NOT threadInfo.toString(): that truncates the stack at 8 frames, which hides
                    // the frame we actually need when diagnosing a shutdown hang (the deep
                    // Network.shutdown()/repository call). Write the FULL stack + held locks.
                    writer.write(formatThreadInfoFull(threadInfo));
                    writer.newLine();
                }
            }

            LOGGER.warn("Shutdown watchdog wrote thread dump: {}", file.getAbsolutePath());
        } catch (Exception e) {
            LOGGER.error("Failed to write on-demand thread dump: {}", e.getMessage(), e);
        }
    }

    /**
     * Renders a ThreadInfo with its FULL stack trace and held locks, unlike {@link ThreadInfo#toString()}
     * which caps the stack at 8 frames. Mirrors that format otherwise so existing dump tooling still parses.
     */
    private static String formatThreadInfoFull(ThreadInfo ti) {
        StringBuilder sb = new StringBuilder();
        sb.append('"').append(ti.getThreadName()).append('"');
        if (ti.isDaemon()) {
            sb.append(" daemon");
        }
        sb.append(" prio=").append(ti.getPriority());
        sb.append(" Id=").append(ti.getThreadId());
        sb.append(' ').append(ti.getThreadState());
        if (ti.getLockName() != null) {
            sb.append(" on ").append(ti.getLockName());
        }
        if (ti.getLockOwnerName() != null) {
            sb.append(" owned by \"").append(ti.getLockOwnerName()).append("\" Id=").append(ti.getLockOwnerId());
        }
        if (ti.isSuspended()) {
            sb.append(" (suspended)");
        }
        if (ti.isInNative()) {
            sb.append(" (in native)");
        }
        sb.append('\n');

        StackTraceElement[] stack = ti.getStackTrace();
        java.lang.management.MonitorInfo[] monitors = ti.getLockedMonitors();
        for (int i = 0; i < stack.length; i++) {
            sb.append("\tat ").append(stack[i]).append('\n');
            for (java.lang.management.MonitorInfo mi : monitors) {
                if (mi.getLockedStackDepth() == i) {
                    sb.append("\t-  locked ").append(mi).append('\n');
                }
            }
        }

        java.lang.management.LockInfo[] synchronizers = ti.getLockedSynchronizers();
        if (synchronizers.length > 0) {
            sb.append("\n\tNumber of locked synchronizers = ").append(synchronizers.length).append('\n');
            for (java.lang.management.LockInfo li : synchronizers) {
                sb.append("\t- ").append(li).append('\n');
            }
        }
        return sb.toString();
    }

    public void shutdown() {

        if( scheduler != null ) {

            LOGGER.info("Shutting Down Thread Dump Scheduler");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }

            LOGGER.info("Thread Dump Scheduler Shutdown Complete");
        }
    }

}
