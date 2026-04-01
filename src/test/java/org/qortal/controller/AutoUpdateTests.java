package org.qortal.controller;

import org.junit.Test;
import org.qortal.ApplyUpdate;

import java.util.ArrayList;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutoUpdateTests {

	@Test
	public void testSanitizeJvmArgumentsReplacesAgentlibAndRemovesJniArgs() {
		List<String> inputArgs = Arrays.asList(
				"-Xmx1g",
				"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005",
				"abort",
				"exit",
				"vfprintf",
				"-Dfoo=bar"
		);

		List<String> sanitized = AutoUpdate.sanitizeJvmArguments(inputArgs);

		assertTrue(sanitized.contains("-Xmx1g"));
		assertTrue(sanitized.contains("-Dfoo=bar"));
		assertTrue(sanitized.contains("-DQORTAL_agentlib=:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"));
		assertFalse(sanitized.contains("abort"));
		assertFalse(sanitized.contains("exit"));
		assertFalse(sanitized.contains("vfprintf"));
	}

	@Test
	public void testBuildApplyUpdateCommandIncludesJvmArgsWhenRequested() {
		List<String> runtimeInputArgs = Arrays.asList("-Xmx2g", "-agentlib:test=foo", "abort");
		String[] savedArgs = new String[]{"--alpha", "beta"};
		String javaExecutable = "/tmp/java";

		List<String> command = AutoUpdate.buildApplyUpdateCommand(
				javaExecutable,
				true,
				runtimeInputArgs,
				savedArgs,
				Paths.get("/tmp/new-qortal.jar")
		);

		assertEquals(javaExecutable, command.get(0));
		assertTrue(command.contains("-Xmx2g"));
		assertTrue(command.contains("-DQORTAL_agentlib=:test=foo"));
		assertFalse(command.contains("abort"));
		assertTrue(command.contains("-cp"));
		assertTrue(command.contains("/tmp/new-qortal.jar"));
		assertTrue(command.contains(ApplyUpdate.class.getCanonicalName()));
		assertTrue(command.contains("--alpha"));
		assertTrue(command.contains("beta"));
	}

	@Test
	public void testBuildApplyUpdateCommandSkipsJvmArgsWhenDisabled() {
		List<String> runtimeInputArgs = Arrays.asList("-Xmx2g", "-agentlib:test=foo");

		List<String> command = AutoUpdate.buildApplyUpdateCommand(
				"java",
				false,
				runtimeInputArgs,
				null,
				Paths.get("/tmp/new-qortal.jar")
		);

		assertEquals("java", command.get(0));
		assertFalse(command.contains("-Xmx2g"));
		assertFalse(command.contains("-DQORTAL_agentlib=:test=foo"));
		assertTrue(command.contains("-cp"));
		assertTrue(command.contains("/tmp/new-qortal.jar"));
		assertTrue(command.contains(ApplyUpdate.class.getCanonicalName()));
	}

	@Test
	public void testBuildJavaCandidatesIncludesPrimaryAndFallbackJava() {
		List<String> candidates = AutoUpdate.buildJavaCandidates(Paths.get("/opt/jdk/bin/java"));

		assertEquals("/opt/jdk/bin/java", candidates.get(0));
		String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (!osName.contains("win")) {
			assertTrue(candidates.contains("/usr/bin/java"));
		}
		assertTrue(candidates.contains("java"));
		assertEquals(candidates.size(), candidates.stream().distinct().count());
	}

	@Test
	public void testSanitizeJvmArgumentsDoesNotMutateInputList() {
		List<String> inputArgs = new ArrayList<>(Arrays.asList("-Xmx1g", "-agentlib:test=foo", "abort"));

		List<String> sanitized = AutoUpdate.sanitizeJvmArguments(inputArgs);

		assertEquals(Arrays.asList("-Xmx1g", "-agentlib:test=foo", "abort"), inputArgs);
		assertTrue(sanitized.contains("-DQORTAL_agentlib=:test=foo"));
		assertFalse(sanitized.contains("abort"));
	}

	@Test
	public void testBuildApplyUpdateCommandHandlesNullSavedArgs() {
		List<String> runtimeInputArgs = Arrays.asList("-Xmx2g");

		List<String> command = AutoUpdate.buildApplyUpdateCommand(
				"java",
				true,
				runtimeInputArgs,
				null,
				Paths.get("/tmp/new-qortal.jar")
		);

		assertTrue(command.contains("-Xmx2g"));
		assertTrue(command.contains("-cp"));
		assertTrue(command.contains("/tmp/new-qortal.jar"));
		assertTrue(command.contains(ApplyUpdate.class.getCanonicalName()));
	}
}
