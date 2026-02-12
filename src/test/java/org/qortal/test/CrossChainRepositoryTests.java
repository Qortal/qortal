package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PublicKeyAccount;
import org.qortal.controller.tradebot.LitecoinACCTv1TradeBot;
import org.qortal.controller.tradebot.TradeBot;
import org.qortal.crosschain.LitecoinACCTv1;
import org.qortal.crosschain.SupportedBlockchain;
import org.qortal.crypto.Crypto;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.utils.NTP;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CrossChainRepositoryTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testExistsAliceTradeWithAtExcludingStates() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String atAddress = Crypto.toATAddress(new byte[64]);
			List<String> endStates = LitecoinACCTv1TradeBot.getInstance().getEndStates();

			TradeBotData bobData = buildTradeBotData(repository, atAddress, LitecoinACCTv1TradeBot.State.BOB_WAITING_FOR_AT_CONFIRM);
			repository.getCrossChainRepository().save(bobData);
			repository.saveChanges();

			assertFalse(repository.getCrossChainRepository().existsAliceTradeWithAtExcludingStates(atAddress, endStates));

			TradeBotData aliceData = buildTradeBotData(repository, atAddress, LitecoinACCTv1TradeBot.State.ALICE_WAITING_FOR_AT_LOCK);
			repository.getCrossChainRepository().save(aliceData);
			repository.saveChanges();

			assertTrue(repository.getCrossChainRepository().existsAliceTradeWithAtExcludingStates(atAddress, endStates));

			aliceData.setState(LitecoinACCTv1TradeBot.State.ALICE_DONE.name());
			aliceData.setStateValue(LitecoinACCTv1TradeBot.State.ALICE_DONE.value);
			repository.getCrossChainRepository().save(aliceData);
			repository.saveChanges();

			assertFalse(repository.getCrossChainRepository().existsAliceTradeWithAtExcludingStates(atAddress, endStates));
		}
	}

	private TradeBotData buildTradeBotData(Repository repository, String atAddress, LitecoinACCTv1TradeBot.State state) {
		byte[] tradePrivateKey = TradeBot.generateTradePrivateKey();

		byte[] tradeNativePublicKey = TradeBot.deriveTradeNativePublicKey(tradePrivateKey);
		byte[] tradeNativePublicKeyHash = Crypto.hash160(tradeNativePublicKey);
		String tradeNativeAddress = Crypto.toAddress(tradeNativePublicKey);

		byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);

		byte[] creatorPublicKey = new byte[32];
		PublicKeyAccount creator = new PublicKeyAccount(repository, creatorPublicKey);

		long timestamp = NTP.getTime();
		long foreignAmount = 1234L;
		long qortAmount = 5678L;
		byte[] receivingAccountInfo = new byte[20];

		return new TradeBotData(tradePrivateKey, LitecoinACCTv1.NAME,
				state.name(), state.value,
				creator.getAddress(), atAddress, timestamp, qortAmount,
				tradeNativePublicKey, tradeNativePublicKeyHash, tradeNativeAddress,
				null, null,
				SupportedBlockchain.LITECOIN.name(),
				tradeForeignPublicKey, tradeForeignPublicKeyHash,
				foreignAmount, null, null, null, receivingAccountInfo);
	}
}
