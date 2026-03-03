package org.qortal.utils;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.NtpV3Packet;
import org.apache.commons.net.ntp.TimeInfo;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class NTP implements Runnable {

	private static final Logger LOGGER = LogManager.getLogger(NTP.class);

	private static boolean isStarted = false;
	private static volatile boolean isStopping = false;
	private static ExecutorService instanceExecutor;
	private static NTP instance;

	private static volatile boolean isOffsetSet = false;
	private static volatile long offset = 0;

	/**
	 * Bootstrap behavior:
	 * Qortal message flow often needs "some" offset quickly (even if imperfect),
	 * and can refine later. So:
	 *  - if offset not set yet, set it as soon as we have a small number of replies
	 *  - once set, switch to stricter quorum + filtering + weighting
	 */
	private static final int BOOTSTRAP_MIN_REPLIES = 3; // fast start
	private static final long MAX_BOOTSTRAP_OFFSET_MS = 5 * 60 * 1000L; // sanity guard: 5 minutes

	static class NTPServer {
		private static final int MIN_POLL = 64;
		private static final int OFFSET_WINDOW = 8;

		public char usage = ' ';
		public final String remote;
		public String refId;
		public Integer stratum;
		public char type = 'u'; // unicast
		public int poll = MIN_POLL;
		public byte reach = 0;
		public Long delay;
		public Double offset;
		public Double jitter;

		private final Deque<Double> offsets = new LinkedList<>();
		private double totalSquareOffsets = 0.0;
		private long nextPoll = 0;
		private Long lastGood;

		public NTPServer(String remote) {
			this.remote = remote;
		}

		public boolean doPoll(NTPUDPClient client, final long now) {
			Thread.currentThread().setName(String.format("NTP: %s", this.remote));

			try {
				boolean isUpdated = false;

				try {
					if (!isOffsetSet)
						LOGGER.trace("NTP poll start remote={}", remote);

					InetAddress address = InetAddress.getByName(remote);
					if (!isOffsetSet)
						LOGGER.trace("NTP resolved remote={} addr={}", remote, address.getHostAddress());

					// CRITICAL: NTPUDPClient is backed by ONE UDP socket. If you call getTime()
					// concurrently, replies can mismatch requests ("Originate time does not match the request").
					// So we MUST serialize getTime() calls.
					final TimeInfo timeInfo;
					synchronized (client) {
						timeInfo = client.getTime(address);
					}

					timeInfo.computeDetails();
					NtpV3Packet ntpMessage = timeInfo.getMessage();

					this.refId = ntpMessage.getReferenceIdString();
					this.stratum = ntpMessage.getStratum();
					this.poll = Math.max(MIN_POLL, 1 << ntpMessage.getPoll());

					this.delay = timeInfo.getDelay();
					Long rawOffset = timeInfo.getOffset();
					this.offset = rawOffset == null ? null : (double) rawOffset;

					if (this.offset != null) {
						if (!isOffsetSet)
							LOGGER.debug("NTP poll ok remote={} stratum={} delayMs={} offsetMs={}", remote, stratum, delay, offset);

						// Maintain small window and compute RMS(offset) as a diagnostic "jitter"
						if (this.offsets.size() == OFFSET_WINDOW) {
							double oldOffset = this.offsets.removeFirst();
							this.totalSquareOffsets -= oldOffset * oldOffset;
						}

						this.offsets.addLast(this.offset);
						this.totalSquareOffsets += this.offset * this.offset;
						this.jitter = Math.sqrt(this.totalSquareOffsets / this.offsets.size());

						// Reachability shift register, keep to 8 bits
						this.reach = (byte) (((this.reach << 1) | 1) & 0xFF);
						this.lastGood = now;

						isUpdated = true;
					} else {
						// No offset computed, treat as failure
						this.reach = (byte) ((this.reach << 1) & 0xFF);
						if (!isOffsetSet)
							LOGGER.debug("NTP poll no-offset remote={}", remote);
					}
				} catch (IOException e) {
					// Failure: shift reach, keep to 8 bits
					this.reach = (byte) ((this.reach << 1) & 0xFF);
					if (!isOffsetSet)
						LOGGER.debug("NTP poll failed remote={} err={}", remote, e.toString());
				}

				this.nextPoll = now + (long) this.poll * 1000L;
				return isUpdated;
			} finally {
				Thread.currentThread().setName("NTP (dormant)");
			}
		}

		public Integer getWhen() {
			if (this.lastGood == null)
				return null;

			return (int) ((System.currentTimeMillis() - this.lastGood) / 1000);
		}
	}

	private final NTPUDPClient client;
	private final List<NTPServer> ntpServers = new ArrayList<>();
	private final ExecutorService serverExecutor;

	private NTP(String[] serverNames) {
		client = new NTPUDPClient();
		client.setDefaultTimeout(2000);

		for (String serverName : serverNames)
			ntpServers.add(new NTPServer(serverName));

		serverExecutor = Executors.newCachedThreadPool();
	}

	public static synchronized void start(String[] serverNames) {
		if (isStarted)
			return;

		// Allow restart after shutdown
		isStopping = false;

		isStarted = true;
		instanceExecutor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r);
			t.setName("NTP instance");
			t.setDaemon(true);
			return t;
		});

		instance = new NTP(serverNames);
		instanceExecutor.execute(instance);
	}

	public static synchronized void shutdownNow() {
		isStopping = true;

		// Best-effort cleanup of instance resources (threads + UDP socket)
		if (instance != null) {
			try {
				instance.shutdownInternals();
			} catch (Exception ignored) {
			}
		}

		if (instanceExecutor != null) {
			instanceExecutor.shutdownNow();
			instanceExecutor = null;
		}

		instance = null;
		isStarted = false;
		isStopping = false;
	}

	public static synchronized void setFixedOffset(Long offset) {
		// Fix offset, e.g. for testing
		NTP.offset = offset != null ? offset : 0L;
		isOffsetSet = true;
	}

	/**
	 * Returns our estimate of internet time.
	 *
	 * @return internet time (ms), or null if unsynchronized.
	 */
	public static Long getTime() {
		if (!isOffsetSet)
			return null;

		return System.currentTimeMillis() + NTP.offset;
	}

	@Override
	public void run() {
		Thread.currentThread().setName("NTP instance");

		try {
			while (!isStopping) {
				Thread.sleep(1000);

				boolean haveUpdates = pollServers();
				if (!haveUpdates)
					continue;

				calculateOffset();
			}
		} catch (InterruptedException e) {
			// Interrupted - time to exit
		} finally {
			try {
				shutdownInternals();
			} catch (Exception ignored) {
			}
		}
	}

	private boolean pollServers() throws InterruptedException {
		if (isStopping)
			return false;

		final long now = System.currentTimeMillis();

		List<NTPServer> pendingServers = ntpServers.stream()
				.filter(ntpServer -> now >= ntpServer.nextPoll)
				.collect(Collectors.toList());

		if (pendingServers.isEmpty())
			return false;

		CompletionService<Boolean> ecs = new ExecutorCompletionService<>(serverExecutor);

		for (NTPServer server : pendingServers) {
			if (isStopping)
				return false;

			try {
				ecs.submit(() -> server.doPoll(client, now));
			} catch (RejectedExecutionException e) {
				// Executor is shutting down
				return false;
			}
		}

		boolean haveUpdate = false;
		for (int i = 0; i < pendingServers.size(); ++i) {
			if (isStopping)
				return false;

			try {
				haveUpdate = ecs.take().get() || haveUpdate;
			} catch (ExecutionException e) {
				// skip
			}
		}

		return haveUpdate;
	}

	private void shutdownInternals() {
		try {
			serverExecutor.shutdownNow();
		} catch (Exception ignored) {
		}

		try {
			client.close();
		} catch (Exception ignored) {
		}
	}

	private void calculateOffset() {
		final int totalServers = ntpServers.size();

		// Replies with offset (regardless of reach)
		int repliesWithOffset = 0;
		double sumOffsets = 0.0;

		for (NTPServer server : ntpServers) {
			if (server.offset == null) {
				server.usage = ' ';
				continue;
			}

			server.usage = '+';
			repliesWithOffset += 1;
			sumOffsets += server.offset;
		}

		// --- Phase 1: Bootstrap (fast, permissive) ---
		// Set offset early so dependent code (e.g., message validity windows) can proceed.
		if (!isOffsetSet) {
			if (repliesWithOffset >= BOOTSTRAP_MIN_REPLIES) {
				double bootstrapMean = sumOffsets / (double) repliesWithOffset;

				// Sanity clamp so a single crazy server doesn't jump us by hours during bootstrap
				if (Math.abs(bootstrapMean) <= MAX_BOOTSTRAP_OFFSET_MS) {
					NTP.offset = Math.round(bootstrapMean);
					isOffsetSet = true;
					LOGGER.debug("Initial NTP offset (bootstrap): {}", NTP.offset);
				} else {
					LOGGER.warn("Bootstrap offset too large ({} ms) - waiting for better samples", (long) bootstrapMean);
				}
			} else {
				LOGGER.debug("NTP bootstrap: repliesWithOffset={} need={}", repliesWithOffset, BOOTSTRAP_MIN_REPLIES);
			}

			// Even if we set offset in bootstrap, we still allow the strict phase below to refine
			// on subsequent cycles.
		}

		// --- Phase 2: Strict / stable (quorum + outlier filtering + weighting) ---
		int requiredReplies = totalServers / 3 + 1;

		// Gather only "eligible" values (offset present, reach nonzero, stratum valid)
		int eligibleCount = 0;
		double eligibleSum = 0.0;
		double eligibleSumSq = 0.0;

		for (NTPServer server : ntpServers) {
			if (server.offset == null) {
				server.usage = ' ';
				continue;
			}

			if (server.reach == 0)
				continue;

			if (server.stratum == null || server.stratum <= 0)
				continue;

			eligibleCount += 1;
			eligibleSum += server.offset;
			eligibleSumSq += server.offset * server.offset;
		}

		if (!isOffsetSet)
			LOGGER.debug("NTP aggregate eligible={} required={} totalServers={}", eligibleCount, requiredReplies, totalServers);

		if (eligibleCount < requiredReplies) {
			if (!isOffsetSet)
				LOGGER.warn("Not enough eligible replies ({}) to refine network time", eligibleCount);
			return;
		}

		final double n = eligibleCount;
		final double mean = eligibleSum / n;

		// Sample stddev for outlier rejection
		double variance = ((n * eligibleSumSq) - (eligibleSum * eligibleSum)) / (n * (n - 1));
		if (variance < 0)
			variance = 0;
		final double thresholdStddev = Math.sqrt(variance);

		// Filter within 1 stddev (you can relax to 1.5 or 2.0 if desired)
		int usefulCount = 0;
		double weightSum = 0.0;
		double weightedOffsetSum = 0.0;

		double filteredSum = 0.0;
		double filteredSumSq = 0.0;

		for (NTPServer server : ntpServers) {
			if (server.offset == null || server.reach == 0)
				continue;

			if (server.stratum == null || server.stratum <= 0)
				continue;

			// If only one eligible, don't filter; otherwise filter outliers
			if (eligibleCount > 1 && Math.abs(server.offset - mean) > thresholdStddev)
				continue;

			server.usage = '*';
			usefulCount += 1;

			filteredSum += server.offset;
			filteredSumSq += server.offset * server.offset;

			// Weight by inverse stratum: lower stratum => more weight
			double weight = 1.0 / (double) server.stratum;
			weightSum += weight;
			weightedOffsetSum += server.offset * weight;
		}

		if (usefulCount <= 1 || weightSum == 0.0) {
			if (!isOffsetSet)
				LOGGER.warn("Not enough useful values ({}) to refine network time (stddev: {})", usefulCount, thresholdStddev);
			return;
		}

		double refinedMean = weightedOffsetSum / weightSum;

		// Diagnostics
		final double fn = usefulCount;
		double filteredVariance = ((fn * filteredSumSq) - (filteredSum * filteredSum)) / (fn * (fn - 1));
		if (filteredVariance < 0)
			filteredVariance = 0;
		double filteredStddev = Math.sqrt(filteredVariance);

		LOGGER.trace(String.format(
			"Threshold stddev: %7.3f, refinedMean: %7.3f, filteredStddev: %7.3f, useful: %d / %d",
			thresholdStddev, refinedMean, filteredStddev, usefulCount, totalServers
		));

		NTP.offset = Math.round(refinedMean);
		isOffsetSet = true;
		LOGGER.debug("Refined NTP offset: {}", NTP.offset);

		// Optional: detailed trace table
		if (LOGGER.getLevel().isLessSpecificThan(Level.TRACE)) {
			LOGGER.trace(() -> String.format("%c%16s %16s %2s %c %4s %4s %3s %7s %7s %7s",
					' ', "remote", "refid", "st", 't', "when", "poll", "reach", "delay", "offset", "jitter"
			));

			for (NTPServer server : ntpServers)
				LOGGER.trace(() -> String.format("%c%16.16s %16.16s %2s %c %4s %4d  %3o  %7s %7s %7s",
						server.usage,
						server.remote,
						formatNull("%s", server.refId, ""),
						formatNull("%2d", server.stratum, ""),
						server.type,
						formatNull("%4d", server.getWhen(), "-"),
						server.poll,
						server.reach & 0xFF,
						formatNull("%5dms", server.delay, ""),
						formatNull("% 5.0fms", server.offset, ""),
						formatNull("%5.2fms", server.jitter, "")
				));
		}
	}

	private static String formatNull(String format, Object arg, String nullOutput) {
		return arg != null ? String.format(format, arg) : nullOutput;
	}
}
