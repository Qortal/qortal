package org.qortal.crosschain;

import org.bitcoinj.core.*;
import org.bitcoinj.params.AbstractBitcoinNetParams;
import org.bitcoinj.store.BlockStore;

/** Transaction/address parameters only. This adapter is deliberately not a header consensus verifier. */
final class BitcoinCloneParameters extends AbstractBitcoinNetParams {
    private final Coin maximumMoney;
    private final Coin dust;

    BitcoinCloneParameters(String identity, int p2pkh, int p2sh, int wif, int xpub, int xprv,
            String hrp, long magic, int port, byte[] genesisHeader, long maxMoney, long dust,
            int coinbaseMaturity) {
        id = "org.qortal.bitcoinclone." + identity;
        addressHeader = p2pkh;
        p2shHeader = p2sh;
        dumpedPrivateKeyHeader = wif;
        bip32HeaderP2PKHpub = xpub;
        bip32HeaderP2PKHpriv = xprv;
        // P2WPKH wallet derivation/signing is not enabled by this profile.
        segwitAddressHrp = hrp;
        packetMagic = magic;
        this.port = port;
        spendableCoinbaseDepth = coinbaseMaturity;
        maximumMoney = Coin.valueOf(maxMoney);
        this.dust = Coin.valueOf(dust);
        // Structural 256-bit bound, not a claimed chain-specific PoW limit.
        maxTarget = java.math.BigInteger.ONE.shiftLeft(256).subtract(java.math.BigInteger.ONE);
        genesisBlock = getSerializer(false).makeBlock(genesisHeader, 0, genesisHeader.length);
        if (genesisBlock.getVersion() < 1 || !genesisBlock.getPrevBlockHash().equals(Sha256Hash.ZERO_HASH)
                || genesisBlock.getMerkleRoot().equals(Sha256Hash.ZERO_HASH) || genesisBlock.getTimeSeconds() <= 0
                || genesisBlock.getDifficultyTargetAsInteger().signum() <= 0
                || genesisBlock.getDifficultyTargetAsInteger().bitLength() > 256
                || genesisBlock.getHash().toBigInteger().compareTo(genesisBlock.getDifficultyTargetAsInteger()) > 0)
            throw new IllegalArgumentException("Invalid SHA256d genesis header");
    }

    @Override public String getPaymentProtocolId() { return id; }
    @Override public Block getGenesisBlock() { return genesisBlock; }
    @Override public Coin getMaxMoney() { return maximumMoney; }
    @Override public Coin getMinNonDustOutput() { return dust; }
    @Override public String getUriScheme() { return "bitcoinclone"; }
    @Override public void checkDifficultyTransitions(StoredBlock previous, Block next, BlockStore store) {
        throw new UnsupportedOperationException("Configured profile trusts Electrum state; no header consensus adapter");
    }
}
