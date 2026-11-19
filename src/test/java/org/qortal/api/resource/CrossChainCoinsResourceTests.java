package org.qortal.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bitcoinj.core.Utils;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.qortal.api.ApiException;
import org.qortal.api.Security;
import org.qortal.api.model.crosschain.ForeignWalletRequest;
import org.qortal.api.model.crosschain.LocalWalletResponse;
import org.qortal.api.model.crosschain.LocalWalletResponseWriter;
import org.qortal.crosschain.*;
import org.qortal.test.common.Common;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CrossChainCoinsResourceTests extends Common {
    @Test public void publicKeyAddressRouteUsesRegistryBackendAndNeverConnects() throws Exception {
        BitcoinCloneDefinition definition = BitcoinCloneLoader.load(getClass().getResourceAsStream("/crosschain/bitcoinclone/regtest.json")).get(0);
        BitcoinCloneRegistry registry = BitcoinCloneRegistry.builder().register(definition).build();
        try (MockedStatic<BitcoinCloneSupport> support = mockStatic(BitcoinCloneSupport.class);
             MockedStatic<Security> security = mockStatic(Security.class)) {
            support.when(BitcoinCloneSupport::getRegistry).thenReturn(registry);
            CrossChainCoinsResource resource = new CrossChainCoinsResource();
            resource.request = mock(javax.servlet.http.HttpServletRequest.class);
            when(resource.request.getLocale()).thenReturn(java.util.Locale.ENGLISH);
            BitcoinClone backend = registry.backend(definition.id, definition.network);
            var master = HDKeyDerivation.createMasterPrivateKey(Utils.HEX.decode("000102030405060708090a0b0c0d0e0f"));
            ForeignWalletRequest data = new ForeignWalletRequest(); data.xpub58 = master.serializePubB58(backend.getNetworkParameters());
            Map<?, ?> address = (Map<?, ?>) payload(resource.address(definition.id, "REGTEST", data));
            assertEquals("mgiHMN7dJsANUWwLfgbiw7hc4kR5xMjPhw", address.get("address"));
            assertEquals("0/0", address.get("relativePath"));
            assertEquals(0, ((ElectrumX) backend.getBlockchainProvider()).getConnectedServerCount());
            data.xpub58 = master.serializePrivB58(backend.getNetworkParameters());
            expectApi(() -> resource.address(definition.id, "REGTEST", data));
            expectApi(() -> resource.coin(definition.id, null));
            expectApi(() -> resource.coin(definition.id, "MAINNET"));
            List<?> coins = (List<?>) payload(resource.coins());
            assertEquals(1, coins.size());
            assertFalse(coins.toString().contains("xprv"));
            assertFalse(coins.toString().contains(data.xpub58));
            assertEquals("10000", ((Map<?, ?>) payload(resource.fees(definition.id, "REGTEST"))).get("feePerKb"));
        }
    }

    @Test public void everyRouteAuthenticatesBeforeRegistryOrWalletAccess() {
        RuntimeException denied = new RuntimeException("denied");
        try (MockedStatic<BitcoinCloneSupport> support = mockStatic(BitcoinCloneSupport.class);
             MockedStatic<Security> security = mockStatic(Security.class)) {
            security.when(() -> Security.checkApiCallAllowed(null)).thenThrow(denied);
            CrossChainCoinsResource resource = new CrossChainCoinsResource();
            for (Runnable route : List.<Runnable>of(resource::coins, () -> resource.coin("fixturecoin", "REGTEST"),
                    () -> resource.fees("fixturecoin", "REGTEST"), () -> resource.height("fixturecoin", "REGTEST"),
                    () -> resource.address("fixturecoin", "REGTEST", null), () -> resource.balance("fixturecoin", "REGTEST", "address"))) {
                try { route.run(); fail("Route did not authenticate"); }
                catch (RuntimeException actual) { assertSame(denied, actual); }
            }
            support.verifyNoInteractions();
        }
    }

    private static Object payload(LocalWalletResponse response) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new LocalWalletResponseWriter().writeTo(response, LocalWalletResponse.class, LocalWalletResponse.class,
                new java.lang.annotation.Annotation[0], javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE, null, bytes);
        return new ObjectMapper().readValue(bytes.toByteArray(), Object.class);
    }

    private static void expectApi(Runnable action) {
        try { action.run(); fail("Expected rejected API request"); }
        catch (ApiException expected) { assertTrue(expected.status >= 400); }
    }
}
