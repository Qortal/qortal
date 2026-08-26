/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * LiteWalletJni code based on https://github.com/PirateNetwork/cordova-plugin-litewallet
 *
 * MIT License
 *
 * Copyright (c) 2020 Zero Currency Coin
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.rust.litewalletjni;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.controller.PirateChainWalletController;

import java.nio.file.Path;
import java.nio.file.Paths;

public class LiteWalletJni {

    protected static final Logger LOGGER = LogManager.getLogger(LiteWalletJni.class);

    /**
     * Selects an encrypted, wallet-specific persistent storage directory in
     * Pirate Unified Wallet. This entry point is absent from the legacy JNI
     * bundle, so callers must only invoke it for a verified unified bundle.
     */
    public static native String configurestorage(final String baseDir, final String passphrase);

    public static native String initlogging();
    public static native String initnew(final String serveruri, final String params, final String saplingOutputb64, final String saplingSpendb64);
    public static native String initfromseed(final String serveruri, final String params, final String seed, final String birthday, final String saplingOutputb64, final String saplingSpendb64);
    public static native String initfromb64(final String serveruri, final String params, final String datab64, final String saplingOutputb64, final String saplingSpendb64);
    public static native String save();

    public static native String execute(final String cmd, final String args);
    public static native String getseedphrase();
    public static native String getseedphrasefromentropy(final String entropy);
    public static native String getseedphrasefromentropyb64(final String entropy64);
    public static native String checkseedphrase(final String input);

    /** Exposes the versioned Unified Wallet JSON service contract. */
    public static native String invokeJson(final String requestJson, final boolean pretty);


    private static boolean loaded = false;

    public static void loadLibrary() {
        if (loaded) {
            return;
        }
        String osName = System.getProperty("os.name");
        String osArchitecture = System.getProperty("os.arch");

        LOGGER.info("OS Name: {}", osName);
        LOGGER.info("OS Architecture: {}", osArchitecture);

        try {
            String libFileName = PirateChainWalletController.getRustLibFilename();
            if (libFileName == null) {
                LOGGER.info("Library not found for OS: {}, arch: {}", osName, osArchitecture);
                return;
            }

            Path libPath = Paths.get(PirateChainWalletController.getRustLibOuterDirectory().toString(), libFileName);
            System.load(libPath.toAbsolutePath().toString());
            loaded = true;
        }
        catch (UnsatisfiedLinkError e) {
            LOGGER.info("Unable to load library");
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static void loadLibraryFrom(Path libPath) {
        if (loaded) {
            return;
        }
        try {
            System.load(libPath.toAbsolutePath().toString());
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            LOGGER.info("Unable to load library");
        }
    }

}
