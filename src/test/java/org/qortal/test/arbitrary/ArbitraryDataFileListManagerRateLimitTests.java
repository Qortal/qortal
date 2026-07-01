package org.qortal.test.arbitrary;

import org.junit.Before;
import org.junit.Test;
import org.qortal.controller.arbitrary.ArbitraryDataFileListManager;
import org.qortal.test.common.Common;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;
import org.qortal.utils.Triple;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertTrue;

public class ArbitraryDataFileListManagerRateLimitTests extends Common {

    @Before
    public void beforeTest() throws Exception {
        Common.useDefaultSettings();
        NTP.setFixedOffset(0L);
    }

    @Test
    public void testFifteenMinuteTierAllowsRetriesAfterFortyAttempts() throws Exception {
        ArbitraryDataFileListManager manager = ArbitraryDataFileListManager.getInstance();
        String signature58 = randomSignature58();

        Field requestsField = ArbitraryDataFileListManager.class.getDeclaredField("arbitraryDataSignatureRequests");
        requestsField.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Triple<Integer, Integer, Long>> requests =
                (Map<String, Triple<Integer, Integer, Long>>) requestsField.get(manager);

        long now = NTP.getTime();
        long sixteenMinutesAgo = now - (16 * 60 * 1000L);
        requests.put(signature58, new Triple<>(40, 0, sixteenMinutesAgo));

        Method shouldMakeMethod = ArbitraryDataFileListManager.class
                .getDeclaredMethod("shouldMakeFileListRequestForSignature", String.class);
        shouldMakeMethod.setAccessible(true);

        boolean allowed = (boolean) shouldMakeMethod.invoke(manager, signature58);
        assertTrue("expected 15-minute tier retry to be allowed at 40 attempts", allowed);
        requests.remove(signature58);
    }

    private String randomSignature58() {
        byte[] signature = new byte[64];
        new Random().nextBytes(signature);
        return Base58.encode(signature);
    }
}
