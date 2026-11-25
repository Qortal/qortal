package org.qortal.crosschain.monero;

/** Monero address prefixes. Network selection must always be explicit. */
public enum MoneroNetwork {

	MAINNET(18, 19, 42),
	TESTNET(53, 54, 63),
	STAGENET(24, 25, 36);

	private final int standardPrefix;
	private final int integratedPrefix;
	private final int subaddressPrefix;

	MoneroNetwork(int standardPrefix, int integratedPrefix, int subaddressPrefix) {
		this.standardPrefix = standardPrefix;
		this.integratedPrefix = integratedPrefix;
		this.subaddressPrefix = subaddressPrefix;
	}

	MoneroAddress.Type addressType(int prefix) {
		if (prefix == this.standardPrefix)
			return MoneroAddress.Type.STANDARD;
		if (prefix == this.integratedPrefix)
			return MoneroAddress.Type.INTEGRATED;
		if (prefix == this.subaddressPrefix)
			return MoneroAddress.Type.SUBADDRESS;
		return null;
	}
}
