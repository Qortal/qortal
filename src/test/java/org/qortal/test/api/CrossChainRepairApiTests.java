package org.qortal.test.api;

import cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.api.ApiError;
import org.qortal.api.resource.CrossChainBitcoinResource;
import org.qortal.api.resource.CrossChainDigibyteResource;
import org.qortal.api.resource.CrossChainDogecoinResource;
import org.qortal.api.resource.CrossChainLitecoinResource;
import org.qortal.api.resource.CrossChainRavencoinResource;
import org.qortal.crosschain.*;
import org.qortal.settings.Settings;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.Common;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CrossChainRepairApiTests extends ApiCommon {

	private static final String BITCOIN_XPRV = "tprv8ZgxMBicQKsPdahhFSrCdvC1bsWyzHHZfTneTVqUXN6s1wEtZLwAkZXzFP6TYLg2aQMecZLXLre5bTVGajEB55L1HYJcawpdFG66STVAWPJ";
	private static final String BITCOIN_XPUB = "tpubDCxs3oB9X7XJYkQGU6gfPwd4h3NEiBGA8mfD1aEbZiG5x3BTH4cJqszDP6dtoHPPjZNEj5jPxuSWHCvjg9AHz4dNg6w5vQhv1B8KwWKpxoz";
	private static final String LITECOIN_XPRV = "tprv8ZgxMBicQKsPdahhFSrCdvC1bsWyzHHZfTneTVqUXN6s1wEtZLwAkZXzFP6TYLg2aQMecZLXLre5bTVGajEB55L1HYJcawpdFG66STVAWPJ";
	private static final String LITECOIN_XPUB = "tpubDCxs3oB9X7XJYkQGU6gfPwd4h3NEiBGA8mfD1aEbZiG5x3BTH4cJqszDP6dtoHPPjZNEj5jPxuSWHCvjg9AHz4dNg6w5vQhv1B8KwWKpxoz";
	private static final String DOGECOIN_XPRV = "dgpv51eADS3spNJh9drNeW1Tc1P9z2LyaQRXPBortsq6yice1k47C2u2Prvgxycr2ihNBWzKZ2LthcBBGiYkWZ69KUTVkcLVbnjq7pD8mnApEru";
	private static final String DOGECOIN_XPUB = "dgub8rqf3khHiPeYE3cNn3Y4DQQ411nAnFpuSUPt5k5GJZQsydsTLkaf4onaWn4N8pHvrV3oNMEATKoPGTFZwm2Uhh7Dy9gYwA7rkSv6oLofbag";
	private static final String DIGIBYTE_XPRV = "xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg";
	private static final String DIGIBYTE_XPUB = "xpub6D7mDwepoJuVF9HFN3LZyEYo8HFq6LZHcM2aKEhnsaXLQqnCWyX3ftGcLDcjYmiPCc9GNX4VjfT32hwvYQnh9H5Z5diAvMsXRrxFmckyNoR";
	private static final String RAVENCOIN_XPRV = "xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg";
	private static final String RAVENCOIN_XPUB = "xpub6D7mDwepoJuVF9HFN3LZyEYo8HFq6LZHcM2aKEhnsaXLQqnCWyX3ftGcLDcjYmiPCc9GNX4VjfT32hwvYQnh9H5Z5diAvMsXRrxFmckyNoR";

	private CrossChainBitcoinResource bitcoinResource;
	private CrossChainLitecoinResource litecoinResource;
	private CrossChainDogecoinResource dogecoinResource;
	private CrossChainDigibyteResource digibyteResource;
	private CrossChainRavencoinResource ravencoinResource;

	private BitcoinyBlockchainProvider bitcoinProviderOriginal;
	private BitcoinyBlockchainProvider litecoinProviderOriginal;
	private BitcoinyBlockchainProvider dogecoinProviderOriginal;
	private BitcoinyBlockchainProvider digibyteProviderOriginal;
	private BitcoinyBlockchainProvider ravencoinProviderOriginal;
	private boolean localAuthBypassOriginal;

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();

		localAuthBypassOriginal = Settings.getInstance().isLocalAuthBypassEnabled();
		FieldUtils.writeField(Settings.getInstance(), "localAuthBypassEnabled", true, true);

		this.bitcoinResource = (CrossChainBitcoinResource) ApiCommon.buildResource(CrossChainBitcoinResource.class);
		this.litecoinResource = (CrossChainLitecoinResource) ApiCommon.buildResource(CrossChainLitecoinResource.class);
		this.dogecoinResource = (CrossChainDogecoinResource) ApiCommon.buildResource(CrossChainDogecoinResource.class);
		this.digibyteResource = (CrossChainDigibyteResource) ApiCommon.buildResource(CrossChainDigibyteResource.class);
		this.ravencoinResource = (CrossChainRavencoinResource) ApiCommon.buildResource(CrossChainRavencoinResource.class);

		bitcoinProviderOriginal = installDummyProvider(Bitcoin.getInstance(), "Bitcoin-TEST");
		litecoinProviderOriginal = installDummyProvider(Litecoin.getInstance(), "Litecoin-TEST");
		dogecoinProviderOriginal = installDummyProvider(Dogecoin.getInstance(), "Dogecoin-TEST");
		digibyteProviderOriginal = installDummyProvider(Digibyte.getInstance(), "Digibyte-TEST");
		ravencoinProviderOriginal = installDummyProvider(Ravencoin.getInstance(), "Ravencoin-TEST");
	}

	@After
	public void afterTest() throws Exception {
		restoreProvider(Bitcoin.getInstance(), bitcoinProviderOriginal);
		restoreProvider(Litecoin.getInstance(), litecoinProviderOriginal);
		restoreProvider(Dogecoin.getInstance(), dogecoinProviderOriginal);
		restoreProvider(Digibyte.getInstance(), digibyteProviderOriginal);
		restoreProvider(Ravencoin.getInstance(), ravencoinProviderOriginal);

		FieldUtils.writeField(Settings.getInstance(), "localAuthBypassEnabled", localAuthBypassOriginal, true);
	}

	@Test
	public void testRepairPreviewEndpoints() {
		assertPreview(bitcoinResource.previewRepairOldWallet(null, BITCOIN_XPUB));
		assertPreview(litecoinResource.previewRepairOldWallet(null, LITECOIN_XPUB));
		assertPreview(dogecoinResource.previewRepairOldWallet(null, DOGECOIN_XPUB));
		assertPreview(digibyteResource.previewRepairOldWallet(null, DIGIBYTE_XPUB));
		assertPreview(ravencoinResource.previewRepairOldWallet(null, RAVENCOIN_XPUB));
	}

	@Test
	public void testRepairRequiresForceWhenNotRecommended() {
		assertApiError(ApiError.INVALID_CRITERIA, () -> bitcoinResource.repairOldWallet(null, null, BITCOIN_XPRV));
		assertApiError(ApiError.INVALID_CRITERIA, () -> litecoinResource.repairOldWallet(null, null, LITECOIN_XPRV));
		assertApiError(ApiError.INVALID_CRITERIA, () -> dogecoinResource.repairOldWallet(null, null, DOGECOIN_XPRV));
		assertApiError(ApiError.INVALID_CRITERIA, () -> digibyteResource.repairOldWallet(null, null, DIGIBYTE_XPRV));
		assertApiError(ApiError.INVALID_CRITERIA, () -> ravencoinResource.repairOldWallet(null, null, RAVENCOIN_XPRV));
	}

	@Test
	public void testRepairForceBypassesRecommendation() {
		assertApiError(ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE, () -> bitcoinResource.repairOldWallet(null, true, BITCOIN_XPRV));
		assertApiError(ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE, () -> litecoinResource.repairOldWallet(null, true, LITECOIN_XPRV));
		assertApiError(ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE, () -> dogecoinResource.repairOldWallet(null, true, DOGECOIN_XPRV));
		assertApiError(ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE, () -> digibyteResource.repairOldWallet(null, true, DIGIBYTE_XPRV));
		assertApiError(ApiError.FOREIGN_BLOCKCHAIN_BALANCE_ISSUE, () -> ravencoinResource.repairOldWallet(null, true, RAVENCOIN_XPRV));
	}

	private void assertPreview(RepairWalletPreview preview) {
		assertNotNull(preview);
		assertFalse(preview.isRepairRecommended());
		assertTrue(preview.getOldBalance() >= 0L);
		assertTrue(preview.getCurrentBalance() >= 0L);
		assertTrue(preview.getMissingBalance() >= 0L);
		assertTrue(preview.getEstimatedFee() >= 0L);
		assertTrue(preview.getDustThreshold() >= 0L);
		assertTrue(preview.getAddressCountOld() > 0);
		assertTrue(preview.getAddressCountCurrent() > 0);
		assertTrue(preview.getMissingUtxoCount() >= 0);
	}

	private BitcoinyBlockchainProvider installDummyProvider(Bitcoiny bitcoiny, String netId) throws IllegalAccessException {
		BitcoinyBlockchainProvider original = bitcoiny.getBlockchainProvider();
		DummyBitcoinyProvider dummyProvider = new DummyBitcoinyProvider(netId);
		dummyProvider.setBlockchain(bitcoiny);
		FieldUtils.writeField(bitcoiny, "blockchainProvider", dummyProvider, true);
		return original;
	}

	private void restoreProvider(Bitcoiny bitcoiny, BitcoinyBlockchainProvider original) throws IllegalAccessException {
		if (original == null)
			return;

		original.setBlockchain(bitcoiny);
		FieldUtils.writeField(bitcoiny, "blockchainProvider", original, true);
	}

	private static class DummyBitcoinyProvider extends BitcoinyBlockchainProvider {
		private final String netId;

		private DummyBitcoinyProvider(String netId) {
			this.netId = netId;
		}

		@Override
		public void setBlockchain(Bitcoiny blockchain) {
		}

		@Override
		public String getNetId() {
			return this.netId;
		}

		@Override
		public int getCurrentHeight() {
			return 0;
		}

		@Override
		public List<CompactBlock> getCompactBlocks(int startHeight, int count) {
			return Collections.emptyList();
		}

		@Override
		public List<byte[]> getRawBlockHeaders(int startHeight, int count) {
			return Collections.emptyList();
		}

		@Override
		public List<Long> getBlockTimestamps(int startHeight, int count) {
			return Collections.emptyList();
		}

		@Override
		public long getConfirmedBalance(byte[] scriptPubKey) {
			return 0L;
		}

		@Override
		public long getConfirmedAddressBalance(String base58Address) {
			return 0L;
		}

		@Override
		public byte[] getRawTransaction(String txHash) {
			return new byte[0];
		}

		@Override
		public byte[] getRawTransaction(byte[] txHash) {
			return new byte[0];
		}

		@Override
		public BitcoinyTransaction getTransaction(String txHash) {
			return new BitcoinyTransaction(txHash, 0, 0, null, Collections.emptyList(), Collections.emptyList());
		}

		@Override
		public List<TransactionHash> getAddressTransactions(byte[] scriptPubKey, boolean includeUnconfirmed) {
			return Collections.emptyList();
		}

		@Override
		public List<BitcoinyTransaction> getAddressBitcoinyTransactions(String address, boolean includeUnconfirmed) {
			return Collections.emptyList();
		}

		@Override
		public List<UnspentOutput> getUnspentOutputs(String address, boolean includeUnconfirmed) {
			return Collections.emptyList();
		}

		@Override
		public List<UnspentOutput> getUnspentOutputs(byte[] scriptPubKey, boolean includeUnconfirmed) {
			return Collections.emptyList();
		}

		@Override
		public void broadcastTransaction(byte[] rawTransaction) {
		}

		@Override
		public Set<ChainableServer> getServers() {
			return Collections.emptySet();
		}

		@Override
		public Set<ChainableServer> getUselessServers() {
			return Collections.emptySet();
		}

		@Override
		public ChainableServer getCurrentServer() {
			return null;
		}

		@Override
		public boolean addServer(ChainableServer server) {
			return false;
		}

		@Override
		public boolean removeServer(ChainableServer server) {
			return false;
		}

		@Override
		public Optional<ChainableServerConnection> setCurrentServer(ChainableServer server, String requestedBy) {
			return Optional.empty();
		}

		@Override
		public List<ChainableServerConnection> getServerConnections() {
			return Collections.emptyList();
		}

		@Override
		public ChainableServer getServer(String hostName, ChainableServer.ConnectionType type, int port) {
			return null;
		}
	}
}
