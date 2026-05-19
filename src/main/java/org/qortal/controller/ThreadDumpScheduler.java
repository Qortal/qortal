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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ThreadDumpScheduler {

    private static final Logger LOGGER = LogManager.getLogger(ThreadDumpScheduler.class);

    private static final String THREAD_DUMP_FOLDER = "thread-dumps";
    private static final String TIMESTAMP_FORMAT = "yyyy-MM-dd_HH-mm-ss";

    private static final long STALE_FILE_TIMEOUT = Settings.getInstance().getThreadDumpExpiration() * 60*60*1000L;

    private ScheduledExecutorService scheduler;
    private File threadDumpDir;

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
            ThreadInfo[] threadInfos = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);

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

                // Write each thread's information
                for (ThreadInfo threadInfo : threadInfos) {
                    writer.write(threadInfo.toString());
                    writer.newLine();
                    writer.newLine();
                }
            }
            
            LOGGER.info("Thread dump generated: {}", threadDumpFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
        }
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
