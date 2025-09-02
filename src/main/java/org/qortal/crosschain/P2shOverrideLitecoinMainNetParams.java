package org.qortal.crosschain;

import org.bitcoinj.core.BitcoinSerializer;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;
import org.libdohj.params.LitecoinMainNetParams;

public class P2shOverrideLitecoinMainNetParams extends NetworkParameters {

    private int p2shHeader;

    private NetworkParameters params = LitecoinMainNetParams.get();

    public P2shOverrideLitecoinMainNetParams(int p2shHeader) {
        super();
        this.p2shHeader = p2shHeader;
    }

    @Override
    public int getP2SHHeader() {
        return this.p2shHeader;
    }

    @Override
    public String getPaymentProtocolId() {
        return this.params.getPaymentProtocolId();
    }

    @Override
    public void checkDifficultyTransitions(StoredBlock storedPrev, Block next, BlockStore blockStore) throws VerificationException, BlockStoreException {
        this.params.checkDifficultyTransitions(storedPrev,next,blockStore);
    }

    @Override
    public Coin getMaxMoney() {
        return this.params.getMaxMoney();
    }

    @Override
    public Coin getMinNonDustOutput() {
        return this.params.getMinNonDustOutput();
    }

    @Override
    public MonetaryFormat getMonetaryFormat() {
        return this.params.getMonetaryFormat();
    }

    @Override
    public String getUriScheme() {
        return this.params.getUriScheme();
    }

    @Override
    public boolean hasMaxMoney() {
        return this.params.hasMaxMoney();
    }

    @Override
    public BitcoinSerializer getSerializer(boolean parseRetain) {
        return this.params.getSerializer(parseRetain);
    }

    @Override
    public int getProtocolVersionNum(ProtocolVersion version) {
        return this.params.getProtocolVersionNum(version);
    }
}
