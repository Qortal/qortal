package org.qortal.test.utils;

import org.junit.Test;
import org.qortal.utils.PidFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PidFileTests {

	@Test
	public void testStalePidIsReplacedWithOurs() throws IOException {
		Path dir = Files.createTempDirectory("pidfile");
		Path pidFile = dir.resolve("run.pid");
		Files.write(pidFile, "936\n".getBytes(StandardCharsets.US_ASCII));

		assertTrue(PidFile.refreshIfPresent(pidFile));

		String contents = new String(Files.readAllBytes(pidFile), StandardCharsets.US_ASCII).trim();
		assertEquals(String.valueOf(ProcessHandle.current().pid()), contents);
	}

	@Test
	public void testNoFileIsCreatedWhenAbsent() throws IOException {
		Path dir = Files.createTempDirectory("pidfile");
		Path pidFile = dir.resolve("run.pid");

		assertFalse(PidFile.refreshIfPresent(pidFile));
		assertFalse(Files.exists(pidFile));
	}

}
