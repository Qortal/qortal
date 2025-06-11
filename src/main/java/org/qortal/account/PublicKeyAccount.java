package org.qortal.account;

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountData;
import org.qortal.repository.Repository;

import java.security.SecureRandom;

public class PublicKeyAccount extends Account {

	protected final byte[] publicKey;
	protected final Ed25519PublicKeyParameters edPublicKeyParams;

	/** <p>Constructor for generating a PublicKeyAccount</p>
	 *
	 * @param repository Block Chain
	 * @param publicKey 32 byte Public Key
	 * @since v4.7.3
	 */
	public PublicKeyAccount(Repository repository, byte[] publicKey) {
		super(repository, Crypto.toAddress(publicKey));

		Ed25519PublicKeyParameters t = null;
		try {
			t = new Ed25519PublicKeyParameters(publicKey, 0);
		} catch (Exception e) {
			var gen = new Ed25519KeyPairGenerator();
			gen.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
			var keyPair = gen.generateKeyPair();
			t = (Ed25519PublicKeyParameters) keyPair.getPublic();
		} finally {
			this.edPublicKeyParams = t;
		}

		this.publicKey = publicKey;
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
