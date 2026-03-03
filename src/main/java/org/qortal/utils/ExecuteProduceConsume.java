package org.qortal.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class ExecuteProduceConsume
 *
 * @ThreadSafe
 */
public abstract class ExecuteProduceConsume implements Runnable {

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class StatsSnapshot {
		public int activeThreadCount = 0;
		public int greatestActiveThreadCount = 0;
		public int consumerCount = 0;
		public int tasksProduced = 0;
		public int tasksConsumed = 0;
		public int spawnFailures = 0;

		public StatsSnapshot() {
		}
	}

	private final String className;
	private final Logger logger;

	protected ExecutorService executor;

	// These are atomic to make this class thread-safe

	private AtomicInteger activeThreadCount = new AtomicInteger(0);
	private AtomicInteger greatestActiveThreadCount = new AtomicInteger(0);
	private AtomicInteger consumerCount = new AtomicInteger(0);
	private AtomicInteger tasksProduced = new AtomicInteger(0);
	private AtomicInteger tasksConsumed = new AtomicInteger(0);
	private AtomicInteger spawnFailures = new AtomicInteger(0);

	/** Whether a new thread has already been spawned and is waiting to start. Used to prevent spawning multiple new threads. */
	private AtomicBoolean hasThreadPending = new AtomicBoolean(false);

	public ExecuteProduceConsume(ExecutorService executor) {
		this.className = this.getClass().getSimpleName();
		this.logger = LogManager.getLogger(this.getClass());

		this.executor = executor;

		this.logger.debug("Created Thread-Safe ExecuteProduceConsume");
	}

	public ExecuteProduceConsume() {
		this(Executors.newCachedThreadPool());
	}

	public void start() {
		this.executor.execute(this);
	}

	public void shutdown() {
		this.executor.shutdownNow();
	}

	public boolean shutdown(long timeout) throws InterruptedException {
		this.executor.shutdownNow();
		return this.executor.awaitTermination(timeout, TimeUnit.MILLISECONDS);
	}

	public StatsSnapshot getStatsSnapshot() {
		StatsSnapshot snapshot = new StatsSnapshot();

		snapshot.activeThreadCount = this.activeThreadCount.get();
		snapshot.greatestActiveThreadCount = this.greatestActiveThreadCount.get();
		snapshot.consumerCount = this.consumerCount.get();
		snapshot.tasksProduced = this.tasksProduced.get();
		snapshot.tasksConsumed = this.tasksConsumed.get();
		snapshot.spawnFailures = this.spawnFailures.get();

		return snapshot;
	}

	protected void onSpawnFailure() {
		/* Allow override in subclasses */
	}

	/**
	 * Returns a Task to be performed, possibly blocking.
	 * 
	 * @param canBlock
	 * @return task to be performed, or null if no task pending.
	 * @throws InterruptedException
	 *
	 * @ThreadSafe
	 */
	protected abstract Task produceTask(boolean canBlock) throws InterruptedException;

	public interface Task {
		String getName();
		void perform() throws InterruptedException;
	}

	@Override
	public void run() {
		Thread.currentThread().setName(this.className + "-" + Thread.currentThread().getId());

		int newActiveCount = this.activeThreadCount.incrementAndGet();
		this.logger.trace("[{}] EPC thread STARTED - activeThreadCount now {}", 
				Thread.currentThread().getId(), newActiveCount);
		
		if (this.activeThreadCount.get() > this.greatestActiveThreadCount.get())
			this.greatestActiveThreadCount.set( this.activeThreadCount.get() );

		// Defer clearing hasThreadPending to prevent unnecessary threads waiting to produce...
		boolean wasThreadPending = this.hasThreadPending.get();
		
		// Track whether we've already decremented activeThreadCount (via normal CAS exit)
		// If not, we must decrement in finally block to prevent count drift on abnormal exit
		boolean alreadyDecrementedActiveCount = false;
		
		// Track exit reason for logging
		String exitReason = "unknown";

		try {
			while (!Thread.currentThread().isInterrupted()) {
				Task task = null;

				if (wasThreadPending) {
					// Clear thread-pending flag now that we about to produce.
					this.hasThreadPending.set( false );
					wasThreadPending = false;
				}

				// If we're the only non-consuming thread - producer can afford to block this round
				boolean canBlock = this.activeThreadCount.get() - this.consumerCount.get() <= 1;

				try {
					task = produceTask(canBlock);
				} catch (InterruptedException e) {
					// We're in shutdown situation so exit
					exitReason = "InterruptedException in produceTask";
					Thread.currentThread().interrupt();
				} catch (Exception e) {
					this.logger.warn(() -> String.format("[%d] exception while trying to produce task", Thread.currentThread().getId()), e);
				}

				if (task == null) {
					// If we have an excess of non-consuming threads then we can exit
					// CRITICAL: Use atomic compare-and-set to prevent race condition where
					// multiple threads could all see "excess > 1" and ALL exit, leaving zero threads.
					// This loop ensures at least one thread always remains.
					while (true) {
						int currentActive = this.activeThreadCount.get();
						int currentConsumers = this.consumerCount.get();
						
						if (currentActive - currentConsumers <= 1) {
							// Not excess - we must continue (at least one producer needed)
							break;
						}
						
						// Try to atomically claim the "exit slot" by decrementing
						// Only succeeds if activeThreadCount hasn't changed since we read it
						if (this.activeThreadCount.compareAndSet(currentActive, currentActive - 1)) {
							alreadyDecrementedActiveCount = true;
							exitReason = String.format("excess threads (was active=%d, consumers=%d)", 
									currentActive, currentConsumers);
							this.logger.debug("[{}] EPC thread exiting - {}", 
									Thread.currentThread().getId(), exitReason);
							return; // Successfully claimed exit slot, safe to exit
						}
						// CAS failed - another thread modified activeThreadCount, loop and recheck
					}

					continue;
				}
				// We have a task

				this.tasksProduced.incrementAndGet();
				this.consumerCount.incrementAndGet();

				// If we have no thread pending and no excess of threads then we should spawn a fresh thread
				if (!this.hasThreadPending.get() && this.activeThreadCount.get() == this.consumerCount.get()) {

					this.hasThreadPending.set( true );

					try {
						this.logger.trace("[{}] Spawning new EPC thread (active={}, consumers={})", 
								Thread.currentThread().getId(), 
								this.activeThreadCount.get(), this.consumerCount.get());
						this.executor.execute(this); // Same object, different thread
					} catch (RejectedExecutionException e) {
						this.spawnFailures.incrementAndGet();
						this.hasThreadPending.set( false );
						this.logger.warn("[{}] Failed to spawn new EPC thread - RejectedExecutionException (spawnFailures={})", 
								Thread.currentThread().getId(), this.spawnFailures.get());
						this.onSpawnFailure();
					}
				}

				try {
					task.perform(); // This can block for a while
				} catch (InterruptedException e) {
					// We're in shutdown situation so exit
					exitReason = "InterruptedException in task.perform";
					Thread.currentThread().interrupt();
				} catch (Exception e) {
					this.logger.warn(() -> String.format("[%d] exception while consuming task", Thread.currentThread().getId()), e);
				}

				this.tasksConsumed.incrementAndGet();
				this.consumerCount.decrementAndGet();
			}
			exitReason = "thread interrupted (normal shutdown)";
		} catch (Throwable t) {
			// CRITICAL: Catch ALL throwables including Error (OutOfMemoryError, StackOverflowError, etc.)
			// This ensures we log the cause before the thread dies
			exitReason = "uncaught Throwable: " + t.getClass().getName() + ": " + t.getMessage();
			this.logger.error("[{}] EPC thread DYING due to uncaught Throwable! activeThreadCount will be {}", 
					Thread.currentThread().getId(), 
					alreadyDecrementedActiveCount ? "unchanged" : "decremented", t);
			// Re-throw to let thread die, but at least we logged it
			if (t instanceof Error) throw (Error) t;
			if (t instanceof RuntimeException) throw (RuntimeException) t;
			throw new RuntimeException(t); // Wrap checked exceptions
		} finally {
			// Ensure activeThreadCount is decremented on ANY exit path (interrupt, error, etc.)
			// to prevent count drift that could cause the EPC to think threads exist when they don't
			int finalActiveCount;
			if (!alreadyDecrementedActiveCount) {
				finalActiveCount = this.activeThreadCount.decrementAndGet();
			} else {
				finalActiveCount = this.activeThreadCount.get();
			}
			
			this.logger.debug("[{}] EPC thread EXITING - reason: {}, activeThreadCount now {}", 
					Thread.currentThread().getId(), exitReason, finalActiveCount);
			
			// CRITICAL WARNING: If this is the last thread, the EPC is now dead!
			if (finalActiveCount == 0) {
				this.logger.error("[{}] CRITICAL: Last EPC thread exiting! EPC is now DEAD - no threads left to produce tasks!", 
						Thread.currentThread().getId());
			}
			
			Thread.currentThread().setName(this.className);
		}
	}
}