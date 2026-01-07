package org.qortal.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.ApplyBootstrap;
import org.qortal.globalization.Translator;
import org.qortal.gui.SysTray;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;

import java.awt.TrayIcon.MessageType;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/* NOTE: It is CRITICAL that we use OpenJDK and not Java SE because our uber jar repacks BouncyCastle which, in turn, unsigns BC causing it to be rejected as a security provider by Java SE. */

public class BootstrapNode {

	public static final String JAR_FILENAME = "qortal.jar";
	public static final String AGENTLIB_JVM_HOLDER_ARG = "-DQORTAL_agentlib=";
	private static final String PARENT_PID_ARG = "--parent-pid";

	private static final Logger LOGGER = LogManager.getLogger(BootstrapNode.class);

	public static boolean attemptToBootstrap() {
		LOGGER.info(String.format("Bootstrapping node..."));

		// Give repository a chance to backup in case things go badly wrong (if enabled)
		if (Settings.getInstance().getRepositoryBackupInterval() > 0) {
			try {
				// Timeout if the database isn't ready for backing up after 60 seconds
				long timeout = 60 * 1000L;
				RepositoryManager.backup(true, "backup", timeout);

			} catch (TimeoutException e) {
				LOGGER.info("Attempt to backup repository failed due to timeout: {}", e.getMessage());
				// Continue with the bootstrap anyway...
			}
		}

		// Call ApplyBootstrap to end this process
		String javaHome = System.getProperty("java.home");
		LOGGER.debug(String.format("Java home: %s", javaHome));

		Path javaBinary = Paths.get(javaHome, "bin", "java");
		LOGGER.debug(String.format("Java binary: %s", javaBinary));

		try {
			List<String> javaCmd = new ArrayList<>();

			// Java runtime binary itself
			javaCmd.add(javaBinary.toString());

			// JVM arguments
			javaCmd.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());

			// Disable, but retain, any -agentlib JVM arg as sub-process might fail if it tries to reuse same port
			javaCmd = javaCmd.stream()
					.map(arg -> arg.replace("-agentlib", AGENTLIB_JVM_HOLDER_ARG))
					.collect(Collectors.toList());

			// Remove JNI options as they won't be supported by command-line 'java'
			// These are typically added by the AdvancedInstaller Java launcher EXE
			javaCmd.removeAll(Arrays.asList("abort", "exit", "vfprintf"));

			// Call ApplyBootstrap using JAR
			javaCmd.addAll(Arrays.asList("-cp", JAR_FILENAME, ApplyBootstrap.class.getCanonicalName()));

			// Add command-line args saved from start-up
			String[] savedArgs = Controller.getInstance().getSavedArgs();
			if (savedArgs != null)
				javaCmd.addAll(Arrays.asList(savedArgs));

			long parentPid = ProcessHandle.current().pid();
			javaCmd.addAll(Arrays.asList(PARENT_PID_ARG, Long.toString(parentPid)));

			LOGGER.info(String.format("Restarting node with: %s", String.join(" ", javaCmd)));

			SysTray.getInstance().showMessage(Translator.INSTANCE.translate("SysTray", "BOOTSTRAP_NODE"),
					Translator.INSTANCE.translate("SysTray", "APPLYING_BOOTSTRAP_AND_RESTARTING"),
					MessageType.INFO);

			ProcessBuilder processBuilder = new ProcessBuilder(javaCmd);

			// New process will inherit our stdout and stderr
			processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
			processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

			Process process = processBuilder.start();

			// Nothing to pipe to new process, so close output stream (process's stdin)
			process.getOutputStream().close();

			return true; // restarting node OK
		} catch (Exception e) {
			LOGGER.error(String.format("Failed to restart node: %s", e.getMessage()));

			return true; // repo was okay, even if applying bootstrap failed
		}
	}
}
