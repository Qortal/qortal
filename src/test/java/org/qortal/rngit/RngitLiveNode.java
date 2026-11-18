package org.qortal.rngit;

import io.reticulum.Reticulum;

import java.nio.file.Path;

import static org.apache.commons.codec.binary.Hex.encodeHexString;

/**
 * Runs an {@link RngitServer} on its own Reticulum instance, without the rest
 * of Core, for {@code reticulum/rngit-livetest/run.sh} to drive with the stock
 * {@code rngit} and {@code git-remote-rns} from Python RNS.
 * <p>
 * Usage: {@code RngitLiveNode <reticulum-config-dir> <rngit-config-dir>}
 */
public class RngitLiveNode {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: RngitLiveNode <reticulum-config-dir> <rngit-config-dir>");
            System.exit(2);
        }

        new Reticulum(Path.of(args[0]).toAbsolutePath().toString());

        RngitServer server = new RngitServer(Path.of(args[1]).toAbsolutePath());
        server.start();

        System.out.println("[rngit-live] destination <" + encodeHexString(server.getDestination().getHash()) + ">");
        System.out.flush();

        Thread.currentThread().join();
    }
}
