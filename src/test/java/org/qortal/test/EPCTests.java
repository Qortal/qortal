package org.qortal.test;

import org.junit.Test;
import org.qortal.utils.ExecuteProduceConsume;
import org.qortal.utils.ExecuteProduceConsume.StatsSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.fail;

public class EPCTests {

	static class SleepTask implements ExecuteProduceConsume.Task {
		private static final Random RANDOM = new Random();

		@Override
		public String getName() {
			return "SleepTask";
		}

		@Override
		public void perform() throws InterruptedException {
			Thread.sleep(RANDOM.nextInt(500) + 100);
		}
	}

	static class RandomEPC extends ExecuteProduceConsume {
		private final int TASK_PERCENT;
		private final int PAUSE_PERCENT;

		public RandomEPC(ExecutorService executor, int taskPercent, int pausePercent) {
			super(executor);

			this.TASK_PERCENT = taskPercent;
			this.PAUSE_PERCENT = pausePercent;
		}

		@Override
		protected Task produceTask(boolean canIdle) throws InterruptedException {
			if (Thread.interrupted())
				throw new InterruptedException();

			Random random = new Random();

			final int percent = random.nextInt(100);

			// Sometimes produce a task
			if (percent < TASK_PERCENT) {
				return new SleepTask();
			} else {
				// If we don't produce a task, then maybe simulate a pause until work arrives
				if (canIdle && percent < PAUSE_PERCENT)
					Thread.sleep(random.nextInt(100));

				return null;
			}
		}
	}

	@Test
	public void testRandomEPC() throws InterruptedException {
		final int TASK_PERCENT = 25; // Produce a task this % of the time
		final int PAUSE_PERCENT = 80; // Pause for new work this % of the time

		final ExecutorService executor = Executors.newCachedThreadPool();

		testEPC(new RandomEPC(executor, TASK_PERCENT, PAUSE_PERCENT));
	}

	@Test
	public void testRandomFixedPoolEPC() throws InterruptedException {
		final int TASK_PERCENT = 25; // Produce a task this % of the time
		final int PAUSE_PERCENT = 80; // Pause for new work this % of the time
		final int MAX_THREADS = 3;

		final ExecutorService executor = Executors.newFixedThreadPool(MAX_THREADS);

		testEPC(new RandomEPC(executor, TASK_PERCENT, PAUSE_PERCENT));
	}

	/**
	 * Test ping scenario with many peers requiring pings.
	 * <p>
	 * Specifically, if:
	 * <ul>
	 * <li>the idling EPC thread sleeps for 1 second</li>
	 * <li>pings are required every P seconds</li>
	 * <li>there are way more than P peers</li>
	 * </ul>
	 * then we need to make sure EPC threads are not
	 * delayed such that some peers (>P) don't get a
	 * chance to be pinged.
	 */
	@Test
	public void testPingEPC() throws InterruptedException {
		final long PRODUCER_SLEEP_TIME = 1000; // ms
		final long PING_INTERVAL = PRODUCER_SLEEP_TIME * 8; // ms
		final long PING_ROUND_TRIP_TIME = PRODUCER_SLEEP_TIME * 5; // ms

		final int MAX_PEERS = 20;

		final List<Long> lastPingProduced = new ArrayList<>(Collections.nCopies(MAX_PEERS, System.currentTimeMillis()));

		class PingTask implements ExecuteProduceConsume.Task {
			private final int peerIndex;
			private final long lastPing;
			private final long productionTimestamp;
			private final String name;

			public PingTask(int peerIndex, long lastPing, long productionTimestamp) {
				this.peerIndex = peerIndex;
				this.lastPing = lastPing;
				this.productionTimestamp = productionTimestamp;
				this.name = "PingTask::[" + this.peerIndex + "]";
			}

			@Override
			public String getName() {
				return name;
			}

			@Override
			public void perform() throws InterruptedException {
				long now = System.currentTimeMillis();

				System.out.println(String.format("Pinging peer %d after post-production delay of %dms and ping interval of %dms",
						peerIndex,
						now - productionTimestamp,
						now - lastPing
				));

				long threshold = now - PING_INTERVAL - PRODUCER_SLEEP_TIME;
				if (lastPing < threshold)
					fail("excessive peer ping interval for peer " + peerIndex);

				// At least half the worst case ping round-trip
				Random random = new Random();
				int halfTime = (int) PING_ROUND_TRIP_TIME / 2;
				long sleep = random.nextInt(halfTime) + halfTime;
				Thread.sleep(sleep);
			}
		}

		class PingEPC extends ExecuteProduceConsume {
			@Override
			protected Task produceTask(boolean canIdle) throws InterruptedException {
				// Is there a peer that needs a ping?
				final long now = System.currentTimeMillis();
				synchronized (lastPingProduced) {
					for (int peerIndex = 0; peerIndex < lastPingProduced.size(); ++peerIndex) {
						long lastPing = lastPingProduced.get(peerIndex);

						if (lastPing < now - PING_INTERVAL) {
							lastPingProduced.set(peerIndex, System.currentTimeMillis());
							return new PingTask(peerIndex, lastPing, now);
						}
					}
				}

				// If we can idle, then we do, to simulate worst case
				if (canIdle)
					Thread.sleep(PRODUCER_SLEEP_TIME);

				// No work to do
				return null;
			}
		}

		System.out.println(String.format("Pings should start after %s milliseconds", PING_INTERVAL));

		testEPC(new PingEPC());
	}

	private void testEPC(ExecuteProduceConsume testEPC) throws InterruptedException {
		final int runTime = 60; // seconds
		System.out.println(String.format("Testing EPC for %s seconds:", runTime));

		final long start = System.currentTimeMillis();

		// Status reports every second (bar waiting for synchronization)
		ScheduledExecutorService statusExecutor = Executors.newSingleThreadScheduledExecutor();

		statusExecutor.scheduleAtFixedRate(
				() -> {
					final StatsSnapshot snapshot = testEPC.getStatsSnapshot();
					final long seconds = (System.currentTimeMillis() - start) / 1000L;
					System.out.println(String.format("After %d second%s, %s", seconds, seconds != 1 ? "s" : "", formatSnapshot(snapshot)));
				},
				0L, 1L, TimeUnit.SECONDS
		);

		testEPC.start();

		// Let it run for a minute
		Thread.sleep(runTime * 1000L);
		statusExecutor.shutdownNow();

		final long before = System.currentTimeMillis();
		testEPC.shutdown(30 * 1000);
		final long after = System.currentTimeMillis();

		System.out.println(String.format("Shutdown took %d milliseconds", after - before));

		final StatsSnapshot snapshot = testEPC.getStatsSnapshot();
		System.out.println("After shutdown, " + formatSnapshot(snapshot));
	}

	private String formatSnapshot(StatsSnapshot snapshot) {
		return String.format("threads: %d active (%d max, %d exhaustion%s), tasks: %d produced / %d consumed",
				snapshot.activeThreadCount, snapshot.greatestActiveThreadCount,
				snapshot.spawnFailures, (snapshot.spawnFailures != 1 ? "s": ""),
				snapshot.tasksProduced, snapshot.tasksConsumed
		);
	}

}
