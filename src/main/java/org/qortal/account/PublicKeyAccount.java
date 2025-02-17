package org.qortal.account;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountData;
import org.qortal.repository.Repository;

import java.util.Objects;

public class PublicKeyAccount extends Account {

    protected final Ed25519PublicKeyParameters edPublicKeyParams;
    protected final byte[] publicKey;

    /**
     * Constructs a PublicKeyAccount from a public key byte array.
     * 
     * @param repository The repository to use for database operations.
     * @param publicKey The public key as a byte array.
     * @throws IllegalArgumentException if the public key is invalid.
     */
    public PublicKeyAccount(Repository repository, byte[] publicKey) {
        this(repository, validatePublicKey(publicKey));
    }

    protected PublicKeyAccount(Repository repository, Ed25519PublicKeyParameters edPublicKeyParams) {
        super(repository, Crypto.toAddress(edPublicKeyParams.getEncoded()));
        this.edPublicKeyParams = edPublicKeyParams;
        this.publicKey = edPublicKeyParams.getEncoded();
    }

    /**
     * Validates the public key before use.
     * 
     * @param publicKey The public key to validate.
     * @return Ed25519PublicKeyParameters object if valid.
     * @throws IllegalArgumentException if the public key is invalid.
     */
    private static Ed25519PublicKeyParameters validatePublicKey(byte[] publicKey) {
        if (publicKey == null || publicKey.length != Ed25519PublicKeyParameters.KEY_SIZE) {
            throw new IllegalArgumentException("Invalid public key size");
        }
        return new Ed25519PublicKeyParameters(publicKey, 0);
    }

    public byte[] getPublicKey() {
        return this.publicKey;
    }

    @Override
    protected AccountData buildPublicKeyAccountData() {
        AccountData accountData = super.buildAccountData();
        accountData.setPublicKey(this.publicKey);
        return accountData;
    }

    /**
     * Verifies the given signature for the message using this account's public key.
     * 
     * @param signature The signature to verify.
     * @param message The original message.
     * @return true if the signature is valid, false otherwise.
     * @throws IllegalStateException if the public key parameters are not initialized.
     */
    public boolean verify(byte[] signature, byte[] message) {
        if (edPublicKeyParams == null) {
            throw new IllegalStateException("Public key parameters not initialized");
        }
        return Crypto.verify(this.publicKey, signature, message);
    }

    /**
     * Generates an address from a public key.
     * 
     * @param publicKey The public key to convert to an address.
     * @return The address string.
     */
    public static String getAddress(byte[] publicKey) {
        return Crypto.toAddress(publicKey);
    }
}
