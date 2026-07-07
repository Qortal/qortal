package org.qortal.test.network.message;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.junit.Assert;
import org.junit.Test;
import org.qortal.data.naming.NameData;
import org.qortal.network.message.NamesMessage;
import org.qortal.test.common.Common;
import org.qortal.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Random;

public class NamesMessageTests {

	@Test
	public void testShortEntryRoundTrip() throws Exception {
		byte[] reference = new byte[64];
		new Random().nextBytes(reference);

		NameData nameData = new NameData(
				"abc",
				"abc",
				Common.getTestAccount(null, "alice").getAddress(),
				"{}",
				1_000_000L,
				null,
				false,
				null,
				reference,
				1);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.write(Ints.toByteArray(1));
		Serialization.serializeSizedStringV2(bytes, nameData.getName());
		Serialization.serializeSizedStringV2(bytes, nameData.getReducedName());
		Serialization.serializeAddress(bytes, nameData.getOwner());
		Serialization.serializeSizedStringV2(bytes, nameData.getData());
		bytes.write(Longs.toByteArray(nameData.getRegistered()));
		bytes.write(Ints.toByteArray(0));
		bytes.write(Ints.toByteArray(0));
		bytes.write(nameData.getReference());
		bytes.write(Ints.toByteArray(nameData.getCreationGroupId()));

		NamesMessage message = (NamesMessage) NamesMessage.fromByteBuffer(0, ByteBuffer.wrap(bytes.toByteArray()));
		Assert.assertNotNull(message);
		Assert.assertEquals(1, message.getNameDataList().size());

		NameData parsed = message.getNameDataList().get(0);
		Assert.assertEquals(nameData.getName(), parsed.getName());
		Assert.assertEquals(nameData.getReducedName(), parsed.getReducedName());
		Assert.assertEquals(nameData.getOwner(), parsed.getOwner());
		Assert.assertEquals(nameData.getData(), parsed.getData());
		Assert.assertEquals(nameData.getRegistered(), parsed.getRegistered());
		Assert.assertEquals(nameData.getCreationGroupId(), parsed.getCreationGroupId());
		Assert.assertArrayEquals(nameData.getReference(), parsed.getReference());
	}

}
