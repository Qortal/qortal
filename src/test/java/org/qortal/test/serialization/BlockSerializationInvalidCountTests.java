package org.qortal.test.serialization;

import org.junit.BeforeClass;
import org.junit.Test;
import org.qortal.block.Block;
import org.qortal.transform.block.BlockTransformer;

import static org.junit.Assert.*;

/** Preserve the old serializer's rejection of invalid buffer capacities without allocating them. */
public class BlockSerializationInvalidCountTests {
	@BeforeClass
	public static void configure() {
		BlockSerializationTests.configure();
	}

	@Test
	public void negativeAtCapacity() throws Exception {
		assertRejected(-1, -65);
	}

	@Test
	public void negativeOutputCapacity() throws Exception {
		assertRejected(-5, -5);
	}

	@Test
	public void overflowedOutputCapacity() throws Exception {
		assertRejected(Integer.MAX_VALUE, -2147483393);
	}

	@Test
	public void overflowedAtCapacity() throws Exception {
		assertRejected(33038210, -2147483326);
	}

	private static void assertRejected(int count, int originalNegativeCapacity) throws Exception {
		for (boolean cached : new boolean[] {false, true}) {
			Block block = BlockSerializationTests.createBlock(0, cached, false, false);
			block.getBlockData().setATCount(count);
			try {
				BlockTransformer.toBytesV2(block);
				fail("Expected invalid AT count to be rejected: " + count);
			} catch (IllegalArgumentException e) {
				assertEquals("Negative initial size: " + originalNegativeCapacity, e.getMessage());
			}
		}
	}
}
