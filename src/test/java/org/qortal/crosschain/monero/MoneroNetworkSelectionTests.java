package org.qortal.crosschain.monero;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.UnmarshalException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;

import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.junit.BeforeClass;
import org.junit.Test;
import org.qortal.settings.Settings;

import static org.junit.Assert.*;

public class MoneroNetworkSelectionTests {
	private static JAXBContext context;

	@BeforeClass
	public static void createContext() throws Exception {
		context = JAXBContextFactory.createContext(new Class[] { Settings.class }, null);
	}

	private Settings settings(String json) throws Exception {
		Unmarshaller unmarshaller = context.createUnmarshaller();
		unmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, "application/json");
		unmarshaller.setProperty(UnmarshallerProperties.JSON_INCLUDE_ROOT, false);
		return unmarshaller.unmarshal(new StreamSource(new StringReader(json)), Settings.class).getValue();
	}

	private void assertNetworkValidationError(Runnable action) {
		try {
			action.run();
			fail("Invalid moneroNetwork must fail instead of using the default network");
		} catch (RuntimeException error) {
			assertTrue(error.getCause() instanceof UnmarshalException);
			assertTrue(error.getMessage().contains("moneroNetwork must be one of:"));
		}
	}

	@Test
	public void preservesAutomaticNetworkPairing() throws Exception {
		assertEquals(MoneroNetwork.MAINNET, settings("{}").getMoneroNetwork());
		assertEquals(MoneroNetwork.STAGENET, settings("{\"isTestNet\":true}").getMoneroNetwork());
		assertEquals(MoneroNetwork.MAINNET, settings("{\"moneroNetwork\":null}").getMoneroNetwork());
		assertEquals(MoneroNetwork.STAGENET, settings("{\"isTestNet\":true,\"moneroNetwork\":null}").getMoneroNetwork());
	}

	@Test
	public void canTestMoneroWithoutChangingQortalNetwork() throws Exception {
		Settings settings = settings("{\"isTestNet\":false,\"moneroNetwork\":\"STAGENET\"}");
		assertFalse(settings.isTestNet());
		assertEquals(MoneroNetwork.STAGENET, settings.getMoneroNetwork());
		assertEquals(12391, settings.getApiPort());
		assertEquals(12392, settings.getListenPort());
	}

	@Test
	public void acceptsNetworkNamesRegardlessOfCaseOrSurroundingWhitespace() throws Exception {
		for (MoneroNetwork network : MoneroNetwork.values()) {
			for (boolean qortalTestnet : new boolean[] { false, true }) {
				for (String value : new String[] { network.name(), network.name().toLowerCase(Locale.ROOT),
						" " + network.name().toLowerCase(Locale.ROOT) + " " }) {
					Settings settings = settings("{\"isTestNet\":" + qortalTestnet + ",\"moneroNetwork\":\"" + value + "\"}");
					assertEquals(value, network, settings.getMoneroNetwork());
					assertEquals(qortalTestnet, settings.isTestNet());
				}
			}
		}
		assertEquals(MoneroNetwork.STAGENET, settings("{\"moneroNetwork\":\"Stagenet\"}").getMoneroNetwork());
	}

	@Test
	public void networkParsingDoesNotDependOnSystemLocale() throws Exception {
		Locale previous = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			assertEquals(MoneroNetwork.MAINNET, settings("{\"moneroNetwork\":\"mainnet\"}").getMoneroNetwork());
		} finally {
			Locale.setDefault(previous);
		}
	}

	@Test
	public void rejectsInvalidExplicitNetworkInsteadOfUsingDefault() throws Exception {
		for (boolean qortalTestnet : new boolean[] { false, true }) {
			for (String value : new String[] { "", " ", "stagenett", "unknown" }) {
				Settings settings = settings("{\"isTestNet\":" + qortalTestnet + ",\"moneroNetwork\":\"" + value + "\"}");
				assertNetworkValidationError(settings::getMoneroNetwork);
			}
		}
	}

	@Test
	public void invalidNetworkFailsSettingsLoadBeforeReplacingCurrentSettings() throws Exception {
		Field instance = Settings.class.getDeclaredField("instance");
		instance.setAccessible(true);
		Object previous = instance.get(null);
		Path file = Files.createTempFile("monero-invalid-settings-", ".json");
		try {
			Files.write(file, "{\"moneroNetwork\":\"stagenett\"}".getBytes(StandardCharsets.UTF_8));
			assertNetworkValidationError(() -> Settings.fileInstance(file.toString()));
			assertSame(previous, instance.get(null));
		} finally {
			// Restore even if a regression incorrectly accepts the invalid settings.
			instance.set(null, previous);
			Files.deleteIfExists(file);
		}
	}
}
