package org.qortal.test;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.qortal.gui.SplashFrame;
import org.qortal.gui.SysTray;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

import java.awt.TrayIcon.MessageType;

public class GuiTests {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testSplashFrame() throws InterruptedException {
		SplashFrame splashFrame = SplashFrame.getInstance();

		Thread.sleep(2000L);

		splashFrame.dispose();
	}

	@Test
	public void testSysTray() throws InterruptedException {
		SysTray.getInstance();

		SysTray.getInstance().showMessage("Testing...", "Tray icon should disappear in 10 seconds", MessageType.INFO);

		Thread.sleep(10_000L);

		SysTray.getInstance().dispose();
	}

}
