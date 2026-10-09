package org.qortal.test.data.transaction;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.junit.BeforeClass;
import org.junit.Test;
import org.qortal.crypto.Crypto;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.utils.Base58;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.security.Security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ChatTransactionDataTests {

	@BeforeClass
	public static void beforeClass() {
		Security.addProvider(new BouncyCastleProvider());
	}

	@Test
	public void testJsonUnmarshalDerivesSenderFromPublicKey() throws JAXBException {
		byte[] senderPublicKey = publicKeyBytes(1);
		String expectedSender = Crypto.toAddress(senderPublicKey);

		ChatTransactionData withoutSender = unmarshalChat(senderPublicKey, null);
		assertArrayEquals(senderPublicKey, withoutSender.getCreatorPublicKey());
		assertEquals(expectedSender, withoutSender.getSender());

		String forgedSender = Crypto.toAddress(publicKeyBytes(2));
		ChatTransactionData withForgedSender = unmarshalChat(senderPublicKey, forgedSender);
		assertArrayEquals(senderPublicKey, withForgedSender.getCreatorPublicKey());
		assertEquals(expectedSender, withForgedSender.getSender());
	}

	private static byte[] publicKeyBytes(int offset) {
		byte[] publicKey = new byte[32];
		for (int i = 0; i < publicKey.length; ++i)
			publicKey[i] = (byte) (i + offset);

		return publicKey;
	}

	private static ChatTransactionData unmarshalChat(byte[] senderPublicKey, String sender) throws JAXBException {
		String senderJson = sender == null ? "" : String.format("\"sender\":\"%s\",", sender);
		String json = String.format(
				"{\"timestamp\":1,\"reference\":\"%s\",\"fee\":\"0\",\"txGroupId\":1," +
						"\"senderPublicKey\":\"%s\",%s\"data\":\"%s\",\"isText\":true,\"isEncrypted\":true}",
				Base58.encode(new byte[64]), Base58.encode(senderPublicKey), senderJson,
				Base58.encode(new byte[] { 1 }));

		JAXBContext context = JAXBContextFactory.createContext(new Class[] { ChatTransactionData.class }, null);
		Unmarshaller unmarshaller = context.createUnmarshaller();
		unmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, "application/json");
		unmarshaller.setProperty(UnmarshallerProperties.JSON_INCLUDE_ROOT, false);

		return unmarshaller.unmarshal(new StreamSource(new StringReader(json)), ChatTransactionData.class).getValue();
	}
}
