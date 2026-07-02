package org.qortal.test.network.message;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.junit.Assert;
import org.junit.Test;
import org.qortal.data.network.OnlineAccountData;
import org.qortal.data.network.TradePresenceData;
import org.qortal.network.message.GetTradePresencesMessage;
import org.qortal.network.message.Message;
import org.qortal.network.message.MessageException;
import org.qortal.network.message.OnlineAccountsV3Message;
import org.qortal.network.message.TradePresencesMessage;
import org.qortal.transform.Transformer;
import org.qortal.utils.Base58;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

public class GroupedMessageValidationTests {

	@FunctionalInterface
	private interface MessageParser {
		Message parse(ByteBuffer bytes) throws MessageException;
	}

	@Test
	public void testOnlineAccountsV3RejectsMalformedTrailingCounts() {
		byte[] firstGroup = buildOnlineAccountsGroup(1_000_000L, (byte) 1, (byte) 2, 111);

		assertRejected(bytes -> OnlineAccountsV3Message.fromByteBuffer(0, bytes), concat(firstGroup, Ints.toByteArray(-1)));
		assertRejected(bytes -> OnlineAccountsV3Message.fromByteBuffer(0, bytes), concat(firstGroup, Ints.toByteArray(0)));
		assertRejected(bytes -> OnlineAccountsV3Message.fromByteBuffer(0, bytes), concat(firstGroup, Ints.toByteArray(Integer.MAX_VALUE)));
		assertRejected(bytes -> OnlineAccountsV3Message.fromByteBuffer(0, bytes), concat(firstGroup, new byte[] { 1, 2, 3 }));
	}

	@Test
	public void testOnlineAccountsV3RejectsMalformedInitialGroup() {
		assertRejected(bytes -> OnlineAccountsV3Message.fromByteBuffer(0, bytes), Ints.toByteArray(1));
		assertRejected(bytes -> OnlineAccountsV3Message.fromByteBuffer(0, bytes), concat(Ints.toByteArray(1), Longs.toByteArray(1_000_000L), new byte[] { 1, 2, 3 }));
	}

	@Test
	public void testOnlineAccountsV3TwoGroupPayloadIsAccepted() throws MessageException {
		byte[] payload = concat(
				buildOnlineAccountsGroup(1_000_000L, (byte) 1, (byte) 2, 111),
				buildOnlineAccountsGroup(2_000_000L, (byte) 3, (byte) 4, 222));

		OnlineAccountsV3Message message = (OnlineAccountsV3Message) OnlineAccountsV3Message.fromByteBuffer(0, ByteBuffer.wrap(payload));
		List<OnlineAccountData> onlineAccounts = message.getOnlineAccounts();

		Assert.assertEquals(2, onlineAccounts.size());
		assertOnlineAccount(onlineAccounts.get(0), 1_000_000L, (byte) 1, (byte) 2, 111);
		assertOnlineAccount(onlineAccounts.get(1), 2_000_000L, (byte) 3, (byte) 4, 222);
	}

	@Test
	public void testTradePresencesRejectsMalformedTrailingCounts() {
		byte[] firstGroup = buildTradePresencesGroup(1_000_000L, (byte) 2, (byte) 3, (byte) 4);

		assertRejected(bytes -> TradePresencesMessage.fromByteBuffer(0, bytes), concat(firstGroup, Ints.toByteArray(-1)));
		assertRejected(bytes -> TradePresencesMessage.fromByteBuffer(0, bytes), concat(firstGroup, Ints.toByteArray(0)));
		assertRejected(bytes -> TradePresencesMessage.fromByteBuffer(0, bytes), concat(firstGroup, Ints.toByteArray(Integer.MAX_VALUE)));
		assertRejected(bytes -> TradePresencesMessage.fromByteBuffer(0, bytes), concat(firstGroup, new byte[] { 1, 2, 3 }));
	}

	@Test
	public void testTradePresencesRejectsMalformedInitialGroup() {
		assertRejected(bytes -> TradePresencesMessage.fromByteBuffer(0, bytes), Ints.toByteArray(1));
		assertRejected(bytes -> TradePresencesMessage.fromByteBuffer(0, bytes), concat(Ints.toByteArray(1), Longs.toByteArray(1_000_000L), new byte[] { 1, 2, 3 }));
	}

	@Test
	public void testTradePresencesTwoGroupPayloadIsAccepted() throws MessageException {
		byte[] payload = concat(
				buildTradePresencesGroup(1_000_000L, (byte) 2, (byte) 3, (byte) 4),
				buildTradePresencesGroup(2_000_000L, (byte) 5, (byte) 6, (byte) 7));

		TradePresencesMessage message = (TradePresencesMessage) TradePresencesMessage.fromByteBuffer(0, ByteBuffer.wrap(payload));
		List<TradePresenceData> tradePresences = message.getTradePresences();

		Assert.assertEquals(2, tradePresences.size());
		assertTradePresence(tradePresences.get(0), 1_000_000L, (byte) 2, (byte) 3, (byte) 4);
		assertTradePresence(tradePresences.get(1), 2_000_000L, (byte) 5, (byte) 6, (byte) 7);
	}

	@Test
	public void testGetTradePresencesRejectsMalformedTrailingCounts() {
		byte[] firstGroup = buildGetTradePresencesGroup(1_000_000L, (byte) 8);

		assertRejected(bytes -> GetTradePresencesMessage.fromByteBuffer(0, bytes), concat(firstGroup, Ints.toByteArray(-1)));
		assertRejected(bytes -> GetTradePresencesMessage.fromByteBuffer(0, bytes), concat(firstGroup, Ints.toByteArray(0)));
		assertRejected(bytes -> GetTradePresencesMessage.fromByteBuffer(0, bytes), concat(firstGroup, Ints.toByteArray(Integer.MAX_VALUE)));
		assertRejected(bytes -> GetTradePresencesMessage.fromByteBuffer(0, bytes), concat(firstGroup, new byte[] { 1, 2, 3 }));
	}

	@Test
	public void testGetTradePresencesRejectsMalformedInitialGroup() {
		assertRejected(bytes -> GetTradePresencesMessage.fromByteBuffer(0, bytes), Ints.toByteArray(1));
		assertRejected(bytes -> GetTradePresencesMessage.fromByteBuffer(0, bytes), concat(Ints.toByteArray(1), Longs.toByteArray(1_000_000L), new byte[] { 1, 2, 3 }));
	}

	@Test
	public void testGetTradePresencesTwoGroupPayloadIsAccepted() throws MessageException {
		byte[] payload = concat(
				buildGetTradePresencesGroup(1_000_000L, (byte) 8),
				buildGetTradePresencesGroup(2_000_000L, (byte) 9));

		GetTradePresencesMessage message = (GetTradePresencesMessage) GetTradePresencesMessage.fromByteBuffer(0, ByteBuffer.wrap(payload));
		List<TradePresenceData> tradePresences = message.getTradePresences();

		Assert.assertEquals(2, tradePresences.size());
		assertTradePresence(tradePresences.get(0), 1_000_000L, (byte) 8);
		assertTradePresence(tradePresences.get(1), 2_000_000L, (byte) 9);
	}

	private static void assertRejected(MessageParser parser, byte[] bytes) {
		try {
			parser.parse(ByteBuffer.wrap(bytes));
			Assert.fail("Expected MessageException");
		} catch (MessageException expected) {
			// expected
		}
	}

	private static void assertOnlineAccount(OnlineAccountData onlineAccountData, long timestamp, byte signatureFill, byte publicKeyFill, int nonce) {
		Assert.assertEquals(timestamp, onlineAccountData.getTimestamp());
		Assert.assertArrayEquals(filledBytes(Transformer.SIGNATURE_LENGTH, signatureFill), onlineAccountData.getSignature());
		Assert.assertArrayEquals(filledBytes(Transformer.PUBLIC_KEY_LENGTH, publicKeyFill), onlineAccountData.getPublicKey());
		Assert.assertEquals(Integer.valueOf(nonce), onlineAccountData.getNonce());
	}

	private static void assertTradePresence(TradePresenceData tradePresenceData, long timestamp, byte publicKeyFill, byte signatureFill, byte addressFill) {
		Assert.assertEquals(timestamp, tradePresenceData.getTimestamp());
		Assert.assertArrayEquals(filledBytes(Transformer.PUBLIC_KEY_LENGTH, publicKeyFill), tradePresenceData.getPublicKey());
		Assert.assertArrayEquals(filledBytes(Transformer.SIGNATURE_LENGTH, signatureFill), tradePresenceData.getSignature());
		Assert.assertEquals(Base58.encode(filledBytes(Transformer.ADDRESS_LENGTH, addressFill)), tradePresenceData.getAtAddress());
	}

	private static void assertTradePresence(TradePresenceData tradePresenceData, long timestamp, byte publicKeyFill) {
		Assert.assertEquals(timestamp, tradePresenceData.getTimestamp());
		Assert.assertArrayEquals(filledBytes(Transformer.PUBLIC_KEY_LENGTH, publicKeyFill), tradePresenceData.getPublicKey());
	}

	private static byte[] buildOnlineAccountsGroup(long timestamp, byte signatureFill, byte publicKeyFill, int nonce) {
		return concat(
				Ints.toByteArray(1),
				Longs.toByteArray(timestamp),
				filledBytes(Transformer.SIGNATURE_LENGTH, signatureFill),
				filledBytes(Transformer.PUBLIC_KEY_LENGTH, publicKeyFill),
				Ints.toByteArray(nonce));
	}

	private static byte[] buildTradePresencesGroup(long timestamp, byte publicKeyFill, byte signatureFill, byte addressFill) {
		return concat(
				Ints.toByteArray(1),
				Longs.toByteArray(timestamp),
				filledBytes(Transformer.PUBLIC_KEY_LENGTH, publicKeyFill),
				filledBytes(Transformer.SIGNATURE_LENGTH, signatureFill),
				filledBytes(Transformer.ADDRESS_LENGTH, addressFill));
	}

	private static byte[] buildGetTradePresencesGroup(long timestamp, byte publicKeyFill) {
		return concat(
				Ints.toByteArray(1),
				Longs.toByteArray(timestamp),
				filledBytes(Transformer.PUBLIC_KEY_LENGTH, publicKeyFill));
	}

	private static byte[] filledBytes(int length, byte value) {
		byte[] bytes = new byte[length];
		Arrays.fill(bytes, value);
		return bytes;
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			for (byte[] part : parts) {
				bytes.write(part);
			}
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		return bytes.toByteArray();
	}

}
