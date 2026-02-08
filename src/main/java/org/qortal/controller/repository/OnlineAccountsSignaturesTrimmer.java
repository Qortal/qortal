package org.qortal.controller.repository;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.block.BlockChain;
import org.qortal.controller.Controller;
import org.qortal.controller.Synchronizer;
import org.qortal.data.block.BlockData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.NTP;

import static java.lang.Thread.NORM_PRIORITY;

public class OnlineAccountsSignaturesTrimmer implements Runnable {

	private static final Logger LOGGER = LogManager.getLogger(OnlineAccountsSignaturesTrimmer.class);

	private static final long INITIAL_SLEEP_PERIOD = 5 * 60 * 1000L + 1234L; // ms

	public void run() {
		Thread.currentThread().setName("Online Accounts trimmer");

		if (Settings.getInstance().isLite()) {
			// Nothing to trim in lite mode
			return;
		}

		int trimStartHeight;

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Don't even start trimming until initial rush has ended
			Thread.sleep(INITIAL_SLEEP_PERIOD);

			trimStartHeight = repository.getBlockRepository().getOnlineAccountsSignaturesTrimHeight();
		} catch (InterruptedException e) {
			if (Controller.isStopping()) {
				LOGGER.info("Online Accounts Signatures Trimming shutting down");
			} else {
				LOGGER.error("Online Accounts Signatures Trimming interrupted during initialization. Restart ASAP. Report this error immediately to the developers.", e);
			}
			return;
		} catch (Exception e) {
			LOGGER.error("Online Accounts Signatures Trimming is not working! Not trying again. Restart ASAP. Report this error immediately to the developers.", e);
			return;
		}

		while (!Controller.isStopping()) {
			try (final Repository repository = RepositoryManager.getRepository()) {

				try {
					repository.discardChanges();

					Thread.sleep(Settings.getInstance().getOnlineSignaturesTrimInterval());

					BlockData chainTip = Controller.getInstance().getChainTip();
					if (chainTip == null || NTP.getTime() == null)
						continue;

					// Don't even attempt if we're mid-sync as our repository requests will be delayed for ages
					if (Synchronizer.getInstance().isSynchronizing())
						continue;

					// Trim blockchain by removing 'old' online accounts signatures
					long upperTrimmableTimestamp = NTP.getTime() - BlockChain.getInstance().getOnlineAccountSignaturesMaxLifetime();
					int upperTrimmableHeight = repository.getBlockRepository().getHeightFromTimestamp(upperTrimmableTimestamp);

					int upperBatchHeight = trimStartHeight + Settings.getInstance().getOnlineSignaturesTrimBatchSize();
					int upperTrimHeight = Math.min(upperBatchHeight, upperTrimmableHeight);

					if (trimStartHeight >= upperTrimHeight)
						continue;

					int numSigsTrimmed = repository.getBlockRepository().trimOldOnlineAccountsSignatures(trimStartHeight, upperTrimHeight);
					repository.saveChanges();

					if (numSigsTrimmed > 0) {
						final int finalTrimStartHeight = trimStartHeight;
						LOGGER.info(() -> String.format("Trimmed %d online accounts signature%s between blocks %d and %d",
								numSigsTrimmed, (numSigsTrimmed != 1 ? "s" : ""),
								finalTrimStartHeight, upperTrimHeight));
					} else {
						// Can we move onto next batch?
						if (upperTrimmableHeight > upperBatchHeight) {
							trimStartHeight = upperBatchHeight;

							repository.getBlockRepository().setOnlineAccountsSignaturesTrimHeight(trimStartHeight);
							repository.saveChanges();

							final int finalTrimStartHeight = trimStartHeight;
							LOGGER.info(() -> String.format("Bumping online accounts signatures base trim height to %d", finalTrimStartHeight));
						}
					}
				} catch (InterruptedException e) {
					if(Controller.isStopping()) {
						LOGGER.info("Online Accounts Signatures Trimming Shutting Down");
					}
					else {
						LOGGER.warn("Online Accounts Signatures Trimming interrupted. Trying again. Report this error immediately to the developers.", e);
					}
				} catch (Exception e) {
					LOGGER.warn("Online Accounts Signatures Trimming stopped working. Trying again. Report this error immediately to the developers.", e);
				}
			} catch (Exception e) {
				LOGGER.error("Online Accounts Signatures Trimming is not working! Not trying again. Restart ASAP. Report this error immediately to the developers.", e);
			}
		}
	}
}
