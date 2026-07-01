package org.qortal.test.network.message;

import org.junit.Assert;
import org.junit.Test;
import org.qortal.network.message.GetTradePresencesMessage;
import org.qortal.network.message.Message;
import org.qortal.network.message.MessageException;
import org.qortal.network.message.OnlineAccountsV3Message;
import org.qortal.network.message.TradePresencesMessage;

import java.nio.ByteBuffer;
import java.util.Collections;

public class GroupedMessageEmptyTests {

	@Test
	public void testOnlineAccountsV3EmptyRoundTrip() throws MessageException {
		OnlineAccountsV3Message messageOut = new OnlineAccountsV3Message(Collections.emptyList());
		Message messageIn = Message.fromByteBuffer(ByteBuffer.wrap(messageOut.toBytes()).asReadOnlyBuffer());

		Assert.assertTrue(messageIn instanceof OnlineAccountsV3Message);
		Assert.assertEquals(0, ((OnlineAccountsV3Message) messageIn).getOnlineAccounts().size());
	}

	@Test
	public void testTradePresencesEmptyRoundTrip() throws MessageException {
		TradePresencesMessage messageOut = new TradePresencesMessage(Collections.emptyList());
		Message messageIn = Message.fromByteBuffer(ByteBuffer.wrap(messageOut.toBytes()).asReadOnlyBuffer());

		Assert.assertTrue(messageIn instanceof TradePresencesMessage);
		Assert.assertEquals(0, ((TradePresencesMessage) messageIn).getTradePresences().size());
	}

	@Test
	public void testGetTradePresencesEmptyRoundTrip() throws MessageException {
		GetTradePresencesMessage messageOut = new GetTradePresencesMessage(Collections.emptyList());
		Message messageIn = Message.fromByteBuffer(ByteBuffer.wrap(messageOut.toBytes()).asReadOnlyBuffer());

		Assert.assertTrue(messageIn instanceof GetTradePresencesMessage);
		Assert.assertEquals(0, ((GetTradePresencesMessage) messageIn).getTradePresences().size());
	}

}
