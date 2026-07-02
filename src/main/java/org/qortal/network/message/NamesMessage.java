package org.qortal.network.message;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortal.data.naming.NameData;
import org.qortal.naming.Name;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class NamesMessage extends Message {

	private static final int SIGNATURE_LENGTH = Transformer.SIGNATURE_LENGTH;
	private static final long MIN_ENTRY_SIZE
		= Transformer.INT_LENGTH + // name length
			Transformer.INT_LENGTH + // reduced name length
			Transformer.ADDRESS_LENGTH + // owner
			Transformer.INT_LENGTH + // data length
			Transformer.LONG_LENGTH + // registered timestamp
			Transformer.INT_LENGTH + // was updated
			Transformer.INT_LENGTH + // is for sale
			SIGNATURE_LENGTH + // reference
			Transformer.INT_LENGTH; // creation group Id

	private List<NameData> nameDataList;

	public NamesMessage(List<NameData> nameDataList) {
		super(MessageType.NAMES);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			bytes.write(Ints.toByteArray(nameDataList.size()));

			for (int i = 0; i < nameDataList.size(); ++i) {
				NameData nameData = nameDataList.get(i);

				Serialization.serializeSizedStringV2(bytes, nameData.getName());

				Serialization.serializeSizedStringV2(bytes, nameData.getReducedName());

				Serialization.serializeAddress(bytes, nameData.getOwner());

				Serialization.serializeSizedStringV2(bytes, nameData.getData());

				bytes.write(Longs.toByteArray(nameData.getRegistered()));

				Long updated = nameData.getUpdated();
				int wasUpdated = (updated != null) ? 1 : 0;
				bytes.write(Ints.toByteArray(wasUpdated));

				if (updated != null) {
					bytes.write(Longs.toByteArray(nameData.getUpdated()));
				}

				int isForSale = nameData.isForSale() ? 1 : 0;
				bytes.write(Ints.toByteArray(isForSale));

				if (nameData.isForSale()) {
					bytes.write(Longs.toByteArray(nameData.getSalePrice()));
				}

				bytes.write(nameData.getReference());

				bytes.write(Ints.toByteArray(nameData.getCreationGroupId()));
			}

		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	public NamesMessage(int id, List<NameData> nameDataList) {
		super(id, MessageType.NAMES);

		this.nameDataList = nameDataList;
	}

	public List<NameData> getNameDataList() {
		return this.nameDataList;
	}


	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {
		try {
			final int nameCount = bytes.getInt();

			// if negative count or count * minimum entry size > remaining, then invalid count
			if (nameCount < 0 || ((long) nameCount * MIN_ENTRY_SIZE) > bytes.remaining())
				throw new MessageException("invalid name count");

			List<NameData> nameDataList = new ArrayList<>(nameCount);

			for (int i = 0; i < nameCount; ++i) {
				String name = Serialization.deserializeSizedStringV2(bytes, Name.MAX_NAME_SIZE);

				String reducedName = Serialization.deserializeSizedStringV2(bytes, Name.MAX_NAME_SIZE);

				String owner = Serialization.deserializeAddress(bytes);

				String data = Serialization.deserializeSizedStringV2(bytes, Name.MAX_DATA_SIZE);

				long registered = bytes.getLong();

				int wasUpdated = bytes.getInt();

				Long updated = null;
				if (wasUpdated == 1) {
					updated = bytes.getLong();
				}

				boolean isForSale = (bytes.getInt() == 1);

				Long salePrice = null;
				if (isForSale) {
					salePrice = bytes.getLong();
				}

				byte[] reference = new byte[SIGNATURE_LENGTH];
				bytes.get(reference);

				int creationGroupId = bytes.getInt();

				NameData nameData = new NameData(name, reducedName, owner, data, registered, updated,
						isForSale, salePrice, reference, creationGroupId);
				nameDataList.add(nameData);
			}

			if (bytes.hasRemaining()) {
				throw new BufferUnderflowException();
			}

			return new NamesMessage(id, nameDataList);

		} catch (TransformationException e) {
			throw new MessageException(e.getMessage(), e);
		}
	}

	public NamesMessage cloneWithNewId(int newId) {
		NamesMessage clone = new NamesMessage(this.nameDataList);
		clone.setId(newId);
		return clone;
	}

}
