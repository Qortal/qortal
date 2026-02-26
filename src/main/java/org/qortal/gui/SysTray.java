package org.qortal.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.controller.Controller;
import org.qortal.globalization.Translator;
import org.qortal.utils.URLViewer;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class SysTray {

	protected static final Logger LOGGER = LogManager.getLogger(SysTray.class);
	private static final String NTP_SCRIPT = "ntpcfg.bat";
	private static final String TRAY_BACKEND_PROPERTY = "qortal.tray.backend";

	private enum TrayBackend {
		NONE,
		AWT,
		LINUX_NATIVE
	}

	private static SysTray instance;
	private TrayBackend backend = TrayBackend.NONE;

	// AWT backend state
	private TrayIcon trayIcon = null;
	private JPopupMenu popupMenu = null;
	/** The hidden dialog has 'focus' when menu displayed so closes the menu when user clicks elsewhere. */
	private JDialog hiddenDialog = null;

	// Linux-native backend state
	private dorkbox.systemTray.SystemTray linuxTray = null;
	private dorkbox.systemTray.SystemTray.TrayType linuxTrayType = null;

	private SysTray() {
		final String requestedBackend = System.getProperty(TRAY_BACKEND_PROPERTY, "auto").trim().toLowerCase(Locale.ROOT);
		final String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		final boolean isLinux = osName.contains("linux");
		final boolean isHeadless = GraphicsEnvironment.isHeadless();
		final boolean awtSupported = this.isAwtSystemTraySupported();

		LOGGER.info(
				"Tray startup: requestedBackend={}, os={}, headless={}, awtSupported={}, xdgSessionType={}, xdgDesktop={}",
				requestedBackend, osName, isHeadless, awtSupported, System.getenv("XDG_SESSION_TYPE"), System.getenv("XDG_CURRENT_DESKTOP")
		);

		if (isHeadless)
			return;

		switch (requestedBackend) {
			case "none":
			case "off":
			case "disabled":
				LOGGER.info("Tray backend explicitly disabled by {}", TRAY_BACKEND_PROPERTY);
				return;

			case "awt":
				this.initializeForcedAwt(awtSupported);
				return;

			case "sni":
			case "linux":
				this.initializeForcedLinuxNative(isLinux, false);
				return;

			case "appindicator":
				this.initializeForcedLinuxNative(isLinux, true);
				return;

			case "auto":
			default:
				this.initializeAutoBackend(isLinux, awtSupported);
		}
	}

	private void initializeAutoBackend(boolean isLinux, boolean awtSupported) {
		if (awtSupported && this.initAwtTray())
			return;

		if (isLinux && this.initLinuxNativeTray(false))
			return;

		if (!awtSupported)
			LOGGER.info("No tray backend initialized: AWT tray unsupported and Linux native tray unavailable");
		else
			LOGGER.info("No tray backend initialized: AWT tray initialization failed and Linux native tray unavailable");
	}

	private void initializeForcedAwt(boolean awtSupported) {
		if (!awtSupported) {
			LOGGER.warn("AWT tray requested but SystemTray is unsupported on this system");
			return;
		}

		if (!this.initAwtTray())
			LOGGER.warn("AWT tray requested but initialization failed");
	}

	private void initializeForcedLinuxNative(boolean isLinux, boolean forceAppIndicator) {
		if (!isLinux) {
			LOGGER.warn("Linux native tray requested on non-Linux OS");
			return;
		}

		if (!this.initLinuxNativeTray(forceAppIndicator))
			LOGGER.warn("Linux native tray requested but initialization failed");
	}

	private boolean isAwtSystemTraySupported() {
		try {
			return SystemTray.isSupported();
		} catch (AWTError e) {
			LOGGER.info("AWT SystemTray support check failed: {}", e.getMessage());
			return false;
		}
	}

	private boolean initAwtTray() {
		LOGGER.info("Initializing system tray backend: awt");

		this.popupMenu = createJPopupMenu();

		Image initialIcon = Gui.loadImage("icons/qortal_ui_tray_synced.png");
		if (initialIcon == null) {
			LOGGER.warn("Unable to initialize AWT tray icon image");
			return false;
		}

		try {
			// Build TrayIcon without AWT PopupMenu (which doesn't support Unicode)...
			this.trayIcon = new TrayIcon(initialIcon, "qortal", null);
			// ...and attach mouse listener instead so we can use JPopupMenu (which does support Unicode)
			this.trayIcon.addMouseListener(new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent me) {
					this.maybePopupMenu(me);
				}

				@Override
				public void mouseReleased(MouseEvent me) {
					this.maybePopupMenu(me);
				}

				private void maybePopupMenu(MouseEvent me) {
					if (me.isPopupTrigger()) {
						// We destroy, then recreate, the hidden dialog to prevent taskbar entries on X11
						if (!popupMenu.isVisible())
							destroyHiddenDialog();

						createHiddenDialog();
						hiddenDialog.setLocation(me.getX() + 1, me.getY() - 1);
						popupMenu.setLocation(me.getX() + 1, me.getY() - 1);

						popupMenu.setInvoker(hiddenDialog);

						hiddenDialog.setVisible(true);
						popupMenu.setVisible(true);
					}
				}
			});

			this.trayIcon.setImageAutoSize(true);
			SystemTray.getSystemTray().add(this.trayIcon);
			this.backend = TrayBackend.AWT;
			LOGGER.info("System tray backend initialized: awt");
			return true;
		} catch (AWTException | RuntimeException e) {
			LOGGER.warn("Unable to initialize AWT tray backend: {}", e.getMessage());
			this.trayIcon = null;
			this.popupMenu = null;
			destroyHiddenDialog();
			return false;
		}
	}

	private boolean initLinuxNativeTray(boolean forceAppIndicator) {
		LOGGER.info("Initializing system tray backend: linux-native");

		try {
			dorkbox.systemTray.SystemTray.FORCE_TRAY_TYPE = forceAppIndicator
					? dorkbox.systemTray.SystemTray.TrayType.AppIndicator
					: dorkbox.systemTray.SystemTray.TrayType.AutoDetect;

			dorkbox.systemTray.SystemTray tray = dorkbox.systemTray.SystemTray.get("qortal");
			if (tray == null)
				return false;

			Image initialIcon = Gui.loadImage("icons/qortal_ui_tray_synced.png");
			if (initialIcon != null)
				tray.setImage(initialIcon);

			this.linuxTray = tray;
			this.linuxTrayType = tray.getType();
			populateLinuxNativeMenu(tray.getMenu());
			this.updateLinuxNativeStatusOrTooltip("qortal");
			this.backend = TrayBackend.LINUX_NATIVE;
			LOGGER.info("System tray backend initialized: linux-native ({})", tray.getType());
			return true;
		} catch (Throwable e) {
			LOGGER.warn("Unable to initialize Linux native tray backend: {}", e.getMessage());
			this.linuxTray = null;
			return false;
		} finally {
			dorkbox.systemTray.SystemTray.FORCE_TRAY_TYPE = dorkbox.systemTray.SystemTray.TrayType.AutoDetect;
		}
	}

	private boolean supportsLinuxNativeTooltip() {
		return this.linuxTrayType != dorkbox.systemTray.SystemTray.TrayType.AppIndicator;
	}

	private String normalizeLinuxNativeText(String text) {
		if (text == null)
			return "";

		String normalized = text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
		if (normalized.length() > 180)
			normalized = normalized.substring(0, 177) + "...";

		return normalized;
	}

	private void updateLinuxNativeStatusOrTooltip(String text) {
		if (this.linuxTray == null)
			return;

		String normalizedText = this.normalizeLinuxNativeText(text);
		if (this.supportsLinuxNativeTooltip())
			this.linuxTray.setTooltip(normalizedText);
		else
			this.linuxTray.setStatus(normalizedText);
	}

	private void populateLinuxNativeMenu(dorkbox.systemTray.Menu menu) {
		menu.add(new dorkbox.systemTray.MenuItem(Translator.INSTANCE.translate("SysTray", "CHECK_TIME_ACCURACY"), actionEvent -> {
			try {
				URLViewer.openWebpage(new URL("https://time.is"));
			} catch (Exception e) {
				LOGGER.error("Unable to open time-check website in browser");
			}
		}));

		menu.add(new dorkbox.systemTray.MenuItem(Translator.INSTANCE.translate("SysTray", "BUILD_VERSION"), actionEvent ->
				JOptionPane.showMessageDialog(null,
						"Qortal Core\n" + Translator.INSTANCE.translate("SysTray", "BUILD_VERSION") + ":\n" + Controller.getInstance().getVersionStringWithoutPrefix(),
						"Qortal Core", 1)));

		menu.add(new dorkbox.systemTray.MenuItem(Translator.INSTANCE.translate("SysTray", "EXIT"), actionEvent -> new ClosingWorker().execute()));
	}

	private void createHiddenDialog() {
		if (hiddenDialog != null)
			return;

		hiddenDialog = new JDialog();
		hiddenDialog.setUndecorated(true);
		hiddenDialog.setSize(10, 10);
		hiddenDialog.addWindowFocusListener(new WindowFocusListener() {
			@Override
			public void windowLostFocus(WindowEvent we) {
				destroyHiddenDialog();
			}

			@Override
			public void windowGainedFocus(WindowEvent we) {
			}
		});
	}

	private void destroyHiddenDialog() {
		if (hiddenDialog == null)
			return;

		hiddenDialog.setVisible(false);
		hiddenDialog.dispose();
		hiddenDialog = null;
	}

	private JPopupMenu createJPopupMenu() {
		JPopupMenu menu = new JPopupMenu();

		menu.addPopupMenuListener(new PopupMenuListener() {
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
				destroyHiddenDialog();
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent e) {
			}
		});

		JMenuItem openTimeCheck = new JMenuItem(Translator.INSTANCE.translate("SysTray", "CHECK_TIME_ACCURACY"));
		openTimeCheck.addActionListener(actionEvent -> {
			destroyHiddenDialog();

			try {
				URLViewer.openWebpage(new URL("https://time.is"));
			} catch (Exception e) {
				LOGGER.error("Unable to open time-check website in browser");
			}
		});
		menu.add(openTimeCheck);

		// Only for Windows users
		if (System.getProperty("os.name").toLowerCase().contains("win")) {
			JMenuItem syncTime = new JMenuItem(Translator.INSTANCE.translate("SysTray", "SYNCHRONIZE_CLOCK"));
			syncTime.addActionListener(actionEvent -> {
				destroyHiddenDialog();

				new SynchronizeClockWorker().execute();
			});
			menu.add(syncTime);
		}

		JMenuItem about = new JMenuItem(Translator.INSTANCE.translate("SysTray", "BUILD_VERSION"));
		about.addActionListener(actionEvent -> {
			destroyHiddenDialog();

			JOptionPane.showMessageDialog(null, "Qortal Core\n" + Translator.INSTANCE.translate("SysTray", "BUILD_VERSION") + ":\n" + Controller.getInstance().getVersionStringWithoutPrefix(), "Qortal Core", 1);
		});
		menu.add(about);

		JMenuItem exit = new JMenuItem(Translator.INSTANCE.translate("SysTray", "EXIT"));
		exit.addActionListener(actionEvent -> {
			destroyHiddenDialog();

			new ClosingWorker().execute();
		});
		menu.add(exit);

		return menu;
	}

	static class SynchronizeClockWorker extends SwingWorker<Void, Void> {
		@Override
		protected Void doInBackground() {
			// Extract reconfiguration script from resources
			String resourceName = "/node-management/" + NTP_SCRIPT;
			Path scriptPath = Paths.get(NTP_SCRIPT);

			try (InputStream in = SysTray.class.getResourceAsStream(resourceName)) {
				Files.copy(in, scriptPath, StandardCopyOption.REPLACE_EXISTING);
			} catch (IllegalArgumentException | IOException e) {
				LOGGER.warn(String.format("Couldn't locate NTP configuration resource: %s", resourceName));
				return null;
			}

			// Now execute extracted script
			List<String> scriptCmd = Arrays.asList(NTP_SCRIPT);
			LOGGER.info(String.format("Running NTP configuration script: %s", String.join(" ", scriptCmd)));
			try {
				new ProcessBuilder(scriptCmd).start();
			} catch (IOException e) {
				LOGGER.warn(String.format("Failed to execute NTP configuration script: %s", e.getMessage()));
				return null;
			}

			return null;
		}
	}

	static class ClosingWorker extends SwingWorker<Void, Void> {
		@Override
		protected Void doInBackground() {
			Controller.getInstance().shutdown();
			return null;
		}

		@Override
		protected void done() {
			System.exit(0);
		}
	}

	public static synchronized SysTray getInstance() {
		if (instance == null)
			instance = new SysTray();

		return instance;
	}

	public void showMessage(String caption, String text, TrayIcon.MessageType messagetype) {
		try {
			switch (this.backend) {
				case AWT:
					if (this.trayIcon != null)
						this.trayIcon.displayMessage(caption, text, messagetype);
					break;

				case LINUX_NATIVE:
					// Linux-native backend doesn't support AWT message bubbles directly.
					LOGGER.debug("Tray notification [{}]: {}", caption, text);
					break;

				case NONE:
				default:
					break;
			}
		} catch (Exception e) {
			LOGGER.debug("Unable to show tray message: {}", e.getMessage());
		}
	}

	public void setToolTipText(String text) {
		try {
			switch (this.backend) {
				case AWT:
					if (this.trayIcon != null)
						this.trayIcon.setToolTip(text);
					break;

				case LINUX_NATIVE:
					this.updateLinuxNativeStatusOrTooltip(text);
					break;

				case NONE:
				default:
					break;
			}
		} catch (Exception e) {
			LOGGER.debug("Unable to set tray tooltip: {}", e.getMessage());
		}
	}

	public void setTrayIcon(int iconid) {
		try {
			Image iconImage = null;
			switch (iconid) {
				case 1:
					iconImage = Gui.loadImage("icons/qortal_ui_tray_syncing_time-alt.png");
					break;
				case 2:
					iconImage = Gui.loadImage("icons/qortal_ui_tray_minting.png");
					break;
				case 3:
					iconImage = Gui.loadImage("icons/qortal_ui_tray_syncing.png");
					break;
				case 4:
					iconImage = Gui.loadImage("icons/qortal_ui_tray_synced.png");
					break;
				default:
					break;
			}

			if (iconImage == null)
				return;

			switch (this.backend) {
				case AWT:
					if (this.trayIcon != null)
						this.trayIcon.setImage(iconImage);
					break;

				case LINUX_NATIVE:
					if (this.linuxTray != null)
						this.linuxTray.setImage(iconImage);
					break;

				case NONE:
				default:
					break;
			}
		} catch (Exception e) {
			LOGGER.info("Unable to set tray icon: {}", e.getMessage());
		}
	}

	public void dispose() {
		if (this.trayIcon != null) {
			try {
				SystemTray.getSystemTray().remove(this.trayIcon);
			} catch (Exception e) {
				LOGGER.debug("Unable to remove AWT tray icon cleanly: {}", e.getMessage());
			}
			this.trayIcon = null;
		}

		destroyHiddenDialog();
		this.popupMenu = null;

		if (this.linuxTray != null) {
			try {
				this.linuxTray.shutdown();
			} catch (Exception e) {
				LOGGER.debug("Unable to shut down Linux native tray cleanly: {}", e.getMessage());
			}
			this.linuxTray = null;
			this.linuxTrayType = null;
		}

		this.backend = TrayBackend.NONE;
	}

}
