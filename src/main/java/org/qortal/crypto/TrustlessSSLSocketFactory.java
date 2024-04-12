package org.qortal.crypto;

import javax.net.ssl.*;
import java.security.cert.X509Certificate;

public abstract class TrustlessSSLSocketFactory {

	/**
	 * Creates a SSLSocketFactory that ignore certificate chain validation because ElectrumX servers use mostly
	 * self signed certificates.
	 */
	private static final TrustManager[] TRUSTLESS_MANAGER = new TrustManager[] {
		new X509TrustManager() {
			public X509Certificate[] getAcceptedIssuers() {
				return null;
			}
			public void checkClientTrusted(X509Certificate[] certs, String authType) {
			}
			public void checkServerTrusted(X509Certificate[] certs, String authType) {
			}
		}
	};

	/**
	 * Install the all-trusting trust manager.
	 */
	private static final SSLContext sc;
	static {
		try {
			sc = SSLContext.getInstance("TLSv1.3");
			sc.init(null, TRUSTLESS_MANAGER, new java.security.SecureRandom());
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static SSLSocketFactory getSocketFactory() {
		return sc.getSocketFactory();
	}
}
