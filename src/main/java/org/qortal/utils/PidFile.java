package org.qortal.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Keeps start.sh's run.pid pointing at the running node.
 *
 * start.sh records the PID it launched, and stop.sh signals that PID. A node relaunched by
 * an auto-update (ApplyUpdate.restartNode) never goes through start.sh, so the file kept the
 * PID of the process that had already exited.
 */
public class PidFile {

	private static final Logger LOGGER = LogManager.getLogger(PidFile.class);

	public static final Path DEFAULT_PATH = Paths.get("run.pid");

	private PidFile() {
	}

	/**
	 * Overwrite an existing PID file with this process's PID.
	 * Does nothing when the file is absent, so nodes started some other way gain no file.
	 *
	 * @return true if the file was updated
	 */
	public static boolean refreshIfPresent(Path pidFile) {
		if (!Files.isRegularFile(pidFile))
			return false;

		long pid = ProcessHandle.current().pid();
		try {
			Files.write(pidFile, (pid + "\n").getBytes(StandardCharsets.US_ASCII));
			return true;
		} catch (IOException e) {
			LOGGER.warn("Unable to update {} with PID {}: {}", pidFile, pid, e.getMessage());
			return false;
		}
	}

}
