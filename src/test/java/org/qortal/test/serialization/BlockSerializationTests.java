package org.qortal.test.serialization;

import io.druid.extendedset.intset.ConciseSet;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.qortal.block.Block;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATStateData;
import org.qortal.data.block.BlockData;
import org.qortal.data.transaction.ATTransactionData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transform.block.BlockTransformation;
import org.qortal.transform.block.BlockTransformer;
import org.qortal.utils.Base58;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.*;

/** Byte fixtures captured from the unmodified serializer at 108bf191d42d710ec617f535af30cfd82fc03c87. */
@RunWith(Parameterized.class)
public class BlockSerializationTests {

	@Parameterized.Parameters
	public static Collection<Object[]> cases() {
		return Arrays.asList(new Object[][] {
			{"v2-empty", 0, false, false, false, true},
			{"v2-one", 1, false, false, false, true},
			{"v2-initial-only", 0, false, true, false, true},
			{"v2-many-and-initial", 256, false, true, false, true},
			{"v2-transactions-and-signatures", 3, false, true, true, true},
			{"v2-cached-empty", 0, true, false, false, true},
			{"v2-cached-one", 1, true, false, false, true},
			{"v2-cached-many", 4096, true, false, false, true},
			{"v2-cached-transactions-and-signatures", 3, true, false, true, true},
			{"v1-empty", 0, false, false, false, false},
			{"v1-one", 1, false, false, false, false},
			{"v1-initial-only", 0, false, true, false, false},
			{"v1-many-and-initial", 256, false, true, false, false},
			{"v1-transactions-and-signatures", 3, false, true, true, false}
		});
	}

	@BeforeClass
	public static void configure() {
		Security.addProvider(new BouncyCastleProvider());
		Settings.fileInstance(BlockSerializationTests.class.getClassLoader().getResource("test-settings-v2.json").getPath());
	}

	private final String name;
	private final int atCount;
	private final boolean cached;
	private final boolean initial;
	private final boolean extras;
	private final boolean v2;

	public BlockSerializationTests(String name, int atCount, boolean cached, boolean initial, boolean extras, boolean v2) {
		this.name = name;
		this.atCount = atCount;
		this.cached = cached;
		this.initial = initial;
		this.extras = extras;
		this.v2 = v2;
	}

	@Test
	public void matchesBaselineBytesAndDecodes() throws Exception {
		Block block = createBlock(this.atCount, this.cached, this.initial, this.extras);
		byte[] serialized = this.v2 ? BlockTransformer.toBytesV2(block) : BlockTransformer.toBytes(block);
		try (InputStream fixture = getClass().getResourceAsStream("/block-serialization/" + this.name + ".bin")) {
			assertNotNull("Missing baseline fixture", fixture);
			assertArrayEquals("Serialization changed from the baseline", fixture.readAllBytes(), serialized);
		}

		ByteBuffer buffer = ByteBuffer.wrap(serialized);
		BlockTransformation decoded = this.v2 ? BlockTransformer.fromByteBufferV2(buffer) : BlockTransformer.fromByteBuffer(buffer);
		assertEquals("Decoder should consume all bytes", 0, buffer.remaining());
		assertEquals(this.atCount, decoded.getBlockData().getATCount());
		assertEquals(block.getBlockData().getATFees(), decoded.getBlockData().getATFees());
		assertEquals(this.extras ? 1 : 0, decoded.getTransactions().size());
		if (this.extras) {
			assertEquals(TransactionType.PAYMENT, decoded.getTransactions().get(0).getType());
			assertEquals(123456789L, ((PaymentTransactionData) decoded.getTransactions().get(0)).getAmount());
		}
		assertArrayEquals(block.getBlockData().getEncodedOnlineAccounts(), decoded.getBlockData().getEncodedOnlineAccounts());
		assertArrayEquals(block.getBlockData().getOnlineAccountsSignatures(), decoded.getBlockData().getOnlineAccountsSignatures());
		if (this.cached)
			// Preserve the existing digest of the cached hash, not the cached hash itself.
			assertArrayEquals(Crypto.digest(block.getAtStatesHash()), decoded.getAtStatesHash());
		if (!this.v2)
			assertEquals("Public length API retains V1 semantics", serialized.length, BlockTransformer.getDataLength(block));
	}

	/** Deterministic, repository-free input for both golden tests and allocation measurements. */
	public static Block createBlock(int atCount, boolean cached, boolean initial, boolean extras) {
		List<ATStateData> states = new ArrayList<>();
		long fees = 0;
		for (int i = 0; i < atCount; ++i) {
			fees += i + 1L;
			if (!cached)
				states.add(new ATStateData(Base58.encode(sequence(25, i)), 1234, sequence(32, i + 1), i + 1L, false));
		}
		if (initial)
			states.add(new ATStateData(Base58.encode(sequence(25, 91)), 1234, sequence(32, 92), 999L, true));

		List<TransactionData> transactions = new ArrayList<>();
		ConciseSet accounts = new ConciseSet();
		byte[] signatures = null;
		if (extras) {
			BaseTransactionData base = new BaseTransactionData(1700000000123L, 0, sequence(64, 7), sequence(32, 8), 1000L, sequence(64, 9));
			transactions.add(new PaymentTransactionData(base, Base58.encode(sequence(25, 10)), 123456789L));
			// Generated AT transactions must not be included in the serialized transaction list.
			transactions.add(new ATTransactionData(base, Base58.encode(sequence(25, 11)), Base58.encode(sequence(25, 12)), 99L, 0L));
			accounts.add(2);
			accounts.add(31);
			signatures = sequence(2 * 64 + 2 * 4, 13);
		}
		BlockData data = new BlockData(4, sequence(128, 1), extras ? 1 : 0, 0L, sequence(64, 2), 1234,
				1700000000456L, sequence(32, 3), sequence(64, 4), atCount, fees,
				extras ? BlockTransformer.encodeOnlineAccounts(accounts) : new byte[0], extras ? 2 : 0,
				extras ? 1700000000000L : null, signatures);
		return cached ? new Block(null, data, transactions, sequence(32, 42)) : new Block(null, data, transactions, states);
	}

	private static byte[] sequence(int length, int seed) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; ++i)
			bytes[i] = (byte) (seed + i * 17);
		return bytes;
	}
}
