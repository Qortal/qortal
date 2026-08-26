package org.qortal.crosschain;

import com.rust.litewalletjni.LiteWalletJni;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.encoders.Base64;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PirateWalletValidator {

    private static final Logger LOGGER = LogManager.getLogger(PirateWalletValidator.class);
    private static final int MIN_WALLET_BYTES = 1024;

    public static void main(String[] args) {
        PirateWalletValidator validator = new PirateWalletValidator();
        int exitCode = validator.run(args);
        System.exit(exitCode);
    }

    private int run(String[] args) {
        if (args == null || args.length == 0) {
            LOGGER.info("No wallet file supplied for validation");
            return 2;
        }

        Path walletPath = Paths.get(args[0]);
        if (!Files.exists(walletPath)) {
            LOGGER.info("Wallet file not found: {}", walletPath);
            return 3;
        }
        if (args.length < 3) {
            LOGGER.info("Missing validator arguments");
            return 4;
        }
        Path libDirectory = Paths.get(args[1]);
        String serverUri = args[2];

        if (!this.loadLibrary(libDirectory)) {
            LOGGER.info("Litewallet JNI library not loaded");
            return 5;
        }
        if (!LiteWalletJni.isLoaded()) {
            LOGGER.info("Litewallet JNI library not loaded");
            return 5;
        }

        ValidationInputs inputs = this.loadInputs(libDirectory);
        if (inputs == null) {
            LOGGER.info("Unable to load wallet validation inputs");
            return 6;
        }

        if (serverUri == null || serverUri.trim().isEmpty()) {
            LOGGER.info("No lightwallet server available for validation");
            return 7;
        }

        byte[] walletBytes;
        try {
            walletBytes = Files.readAllBytes(walletPath);
        } catch (IOException e) {
            LOGGER.info("Unable to read wallet file: {}", e.getMessage());
            return 8;
        }

        if (walletBytes.length < MIN_WALLET_BYTES) {
            LOGGER.info("Wallet file too small to validate (size={})", walletBytes.length);
            return 9;
        }

        String wallet64 = Base64.toBase64String(walletBytes);
        String response = LiteWalletJni.initfromb64(serverUri, inputs.params, wallet64,
                inputs.saplingOutput64, inputs.saplingSpend64);
        if (response == null) {
            LOGGER.info("Wallet validation failed: initfromb64 returned null");
            return 10;
        }
        if (response.contains("failed to fill whole buffer")) {
            LOGGER.info("Wallet validation failed: {}", response);
            return 11;
        }
        if (!isInitSuccess(response)) {
            LOGGER.info("Wallet validation failed: {}", response);
            return 12;
        }

        String info = LiteWalletJni.execute("info", "");
        if (info == null || info.trim().isEmpty()) {
            LOGGER.info("Wallet validation failed: info response empty");
            return 13;
        }

        return 0;
    }

    private boolean loadLibrary(Path libDirectory) {
        String libFileName = getRustLibFilename();
        if (libFileName == null) {
            LOGGER.info("Library not found for current OS/arch");
            return false;
        }
        Path libPath = libDirectory.resolve(libFileName);
        if (!Files.exists(libPath)) {
            LOGGER.info("Library file not found: {}", libPath);
            return false;
        }
        LiteWalletJni.loadLibraryFrom(libPath);
        return LiteWalletJni.isLoaded();
    }

    private ValidationInputs loadInputs(Path libDirectory) {
        try {
            Path paramsPath = libDirectory.resolve("coinparams.json");
            Path saplingOutputPath = libDirectory.resolve("saplingoutput_base64");
            Path saplingSpendPath = libDirectory.resolve("saplingspend_base64");
            if (!Files.exists(paramsPath) || !Files.exists(saplingOutputPath) || !Files.exists(saplingSpendPath)) {
                return null;
            }
            String params = Files.readString(paramsPath);
            String saplingOutput64 = Files.readString(saplingOutputPath);
            String saplingSpend64 = Files.readString(saplingSpendPath);
            return new ValidationInputs(params, saplingOutput64, saplingSpend64);
        } catch (IOException e) {
            LOGGER.info("Unable to read wallet validation inputs: {}", e.getMessage());
            return null;
        }
    }

    private static String getRustLibFilename() {
        String osName = System.getProperty("os.name");
        String osArchitecture = System.getProperty("os.arch");

        if (osName.equals("Mac OS X") && osArchitecture.equals("x86_64")) {
            return "librust-macos-x86_64.dylib";
        } else if (osName.equals("Mac OS X") && osArchitecture.equals("aarch64")) {
            return "librust-macos-aarch64.dylib";
        } else if ((osName.equals("Linux") || osName.equals("FreeBSD")) && osArchitecture.equals("aarch64")) {
            return "librust-linux-aarch64.so";
        } else if ((osName.equals("Linux") || osName.equals("FreeBSD")) && osArchitecture.equals("amd64")) {
            return "librust-linux-x86_64.so";
        } else if (osName.contains("Windows") && osArchitecture.equals("amd64")) {
            return "librust-windows-x86_64.dll";
        }

        return null;
    }

    private static boolean isInitSuccess(String response) {
        return response.contains("\"initialized\":true") || response.contains("\"initalized\":true");
    }

    private static final class ValidationInputs {
        private final String params;
        private final String saplingOutput64;
        private final String saplingSpend64;

        private ValidationInputs(String params, String saplingOutput64, String saplingSpend64) {
            this.params = params;
            this.saplingOutput64 = saplingOutput64;
            this.saplingSpend64 = saplingSpend64;
        }
    }
}
