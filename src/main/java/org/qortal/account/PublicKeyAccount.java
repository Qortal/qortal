package org.qortal.account;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountData;
import org.qortal.repository.Repository;
import java.util.Arrays;

public class PublicKeyAccount extends Account {

	protected final byte[] publicKey;
	protected final Ed25519PublicKeyParameters edPublicKeyParams;
	private static final Logger LOGGER = LogManager.getLogger(PublicKeyAccount.class);
	public static final byte[] ALL_ZEROS = new byte[32];

	/** <p>Constructor for generating a PublicKeyAccount</p>
	 *
	 * @param repository Block Chain
	 * @param publicKey 32 byte Public Key
	 * @since v4.7.3
	 * @since v6.0.0 - Updated for Bouncy Castle v1.73
	 */
	public PublicKeyAccount(Repository repository, byte[] publicKey) {
        super(repository, Crypto.toAddress(publicKey));

		this.publicKey = publicKey;

		if (Arrays.equals(publicKey, ALL_ZEROS)) {
			LOGGER.trace("We were passed a null public key");
			this.edPublicKeyParams = null;
			return;
		}

		Ed25519PublicKeyParameters t = null;

		try {
			t = new Ed25519PublicKeyParameters(publicKey, 0);
		} catch (Exception e) {
			LOGGER.error("Failed to generate public key");
		}

		this.edPublicKeyParams = t;
	}

	protected PublicKeyAccount(Repository repository, Ed25519PublicKeyParameters edPublicKeyParams) {
		super(repository, Crypto.toAddress(edPublicKeyParams.getEncoded()));

		this.edPublicKeyParams = edPublicKeyParams;
		this.publicKey = edPublicKeyParams.getEncoded();
	}

	protected PublicKeyAccount(Repository repository, byte[] publicKey, String address) {
		super(repository, address);

		this.publicKey = publicKey;
		this.edPublicKeyParams = null;
	}

	protected PublicKeyAccount() {
		this.publicKey = null;
		this.edPublicKeyParams = null;
	}

	public byte[] getPublicKey() {
		return this.publicKey;
	}

	@Override
	protected AccountData buildAccountData() {
		AccountData accountData = super.buildAccountData();
		accountData.setPublicKey(this.publicKey);
		return accountData;
	}

	public boolean verify(byte[] signature, byte[] message) {
		return Crypto.verify(this.publicKey, signature, message);
	}

	public static String getAddress(byte[] publicKey) {
		return Crypto.toAddress(publicKey);
	}

}
