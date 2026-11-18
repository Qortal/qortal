package org.qortal.crosschain.monero;

import org.junit.Test;
import org.qortal.settings.Settings;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

public class MoneroNetworkSelectionTests {
    private Settings settings(boolean testnet, MoneroNetwork override) throws Exception {
        Constructor<Settings> constructor = Settings.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Settings settings = constructor.newInstance();
        Field mode = Settings.class.getDeclaredField("isTestNet");
        mode.setAccessible(true);
        mode.set(settings, testnet);
        Field network = Settings.class.getDeclaredField("moneroNetwork");
        network.setAccessible(true);
        network.set(settings, override);
        return settings;
    }
    @Test public void preservesAutomaticNetworkPairing() throws Exception {
        assertEquals(MoneroNetwork.MAINNET, settings(false, null).getMoneroNetwork());
        assertEquals(MoneroNetwork.STAGENET, settings(true, null).getMoneroNetwork());
    }
    @Test public void canTestMoneroWithoutChangingQortalNetwork() throws Exception {
        Settings settings = settings(false, MoneroNetwork.STAGENET);
        assertFalse(settings.isTestNet());
        assertEquals(MoneroNetwork.STAGENET, settings.getMoneroNetwork());
        assertEquals(12391, settings.getApiPort());
        assertEquals(12392, settings.getListenPort());
    }
}
