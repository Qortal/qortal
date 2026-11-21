package org.qortal.crosschain;

import org.bitcoinj.core.Coin;

/** Common fee-rate policy for Bitcoin-family wallet funding and contract recovery. */
public final class BitcoinyFees {
    private BitcoinyFees() { }

    public static long perKb(Bitcoiny coin) throws ForeignBlockchainException {
        Coin configured = coin.getFeePerKb();
        long relay = coin.getBlockchainProvider().getMinimumRelayFeePerKb();
        if (configured == null || configured.value <= 0 || relay < 0)
            throw new ForeignBlockchainException("Network or configured fee rate is unavailable");
        return Math.max(configured.value, relay);
    }

    /** An explicitly selected rate is never silently raised above the caller's choice. */
    public static Coin spendRate(Bitcoiny coin, Long feePerByte) throws ForeignBlockchainException {
        if (feePerByte == null) return Coin.valueOf(perKb(coin));
        try {
            long selected = Math.multiplyExact(feePerByte, 1000L);
            long relay = coin.getBlockchainProvider().getMinimumRelayFeePerKb();
            if (feePerByte <= 0 || relay < 0 || selected < relay)
                throw new ForeignBlockchainException("Selected fee rate is below the reported network relay minimum");
            return Coin.valueOf(selected);
        } catch (ArithmeticException error) {
            throw new ForeignBlockchainException("Selected fee rate is too large");
        }
    }
}
