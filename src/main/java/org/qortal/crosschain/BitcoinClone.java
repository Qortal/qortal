package org.qortal.crosschain;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.Transaction;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.qortal.crosschain.BitcoinCloneDefinition.Capability.*;

/** Generic Bitcoin-compatible backend: all wallet/UTXO/signing work stays in Bitcoiny. */
public class BitcoinClone extends Bitcoiny {
    private final BitcoinCloneDefinition definition;
    private final AtomicLong requiredFee;

    protected BitcoinClone(BitcoinCloneDefinition definition, BitcoinyBlockchainProvider provider, Context context) {
        super(provider, context, definition.symbol, Coin.valueOf(definition.feePerKb));
        if (definition.parameters() != context.getParams()) throw new IllegalArgumentException("Parameter adapter mismatch");
        this.definition = definition;
        this.requiredFee = new AtomicLong(definition.p2shFee);
        provider.setBlockchain(this);
    }

    /** No connection is opened until a wallet operation needs one. */
    public static BitcoinClone create(BitcoinCloneDefinition definition) {
        if (definition.isBuiltIn()) throw new IllegalArgumentException("Use the existing built-in singleton factory");
        if (!definition.enabled) throw new IllegalStateException("Wallet is disabled");
        ElectrumX provider = new ElectrumX(definition.key(), definition.genesisHash,
                definition.servers.stream().map(BitcoinCloneDefinition.Endpoint::toServer).collect(Collectors.toList()),
                Map.of(ChainableServer.ConnectionType.TCP, 50001, ChainableServer.ConnectionType.SSL, 50002),
                server -> definition.servers.stream().anyMatch(endpoint -> endpoint.host.equals(server.getHostName())
                        && endpoint.port == server.getPort() && endpoint.transport == server.getConnectionType()));
        return new BitcoinClone(definition, provider, new Context(definition.parameters()));
    }

    public final BitcoinCloneDefinition getDefinition() { return definition; }
    @Override public void setFeePerKb(Coin fee) {
        if (!definition.isBuiltIn() && (fee == null || fee.value < 1000 || fee.value > 100_000_000L))
            throw new IllegalArgumentException("Fee exceeds the configured protocol profile");
        super.setFeePerKb(fee);
    }
    @Override public long getMinimumOrderAmount() { return definition.minimumOrderAmount; }
    @Override public long getP2shFee(Long timestamp) throws ForeignBlockchainException { return requiredFee.get(); }
    @Override public long getFeeRequired() { return requiredFee.get(); }
    @Override public void setFeeRequired(long fee) {
        if (fee <= 0 || fee > 100_000_000L) throw new IllegalArgumentException("Invalid fee");
        requiredFee.set(fee);
    }

    @Override public Transaction buildSpend(String key, String recipient, long amount, Long feePerByte) {
        definition.require(SIGN);
        checkSpend(amount, feePerByte);
        return checkTransaction(super.buildSpend(key, recipient, amount, feePerByte));
    }

    @Override public Transaction buildSpendMultiple(String key, Map<String, Long> recipients, Long feePerByte) {
        definition.require(SIGN);
        if (!definition.isBuiltIn() && (recipients == null || recipients.isEmpty() || recipients.size() > 100))
            throw new IllegalArgumentException("Invalid recipients");
        if (!definition.isBuiltIn()) recipients.values().forEach(amount -> checkSpend(amount, feePerByte));
        return checkTransaction(super.buildSpendMultiple(key, recipients, feePerByte));
    }

    private void checkSpend(long amount, Long feePerByte) {
        if (!definition.isBuiltIn() && (amount < 546 || amount > params.getMaxMoney().value
                || feePerByte != null && (feePerByte < 1 || feePerByte > 100_000)))
            throw new IllegalArgumentException("Unsupported amount or fee");
    }

    private Transaction checkTransaction(Transaction transaction) {
        if (!definition.isBuiltIn() && transaction != null
                && (transaction.bitcoinSerialize().length > 100000 || transaction.getVersion() != 1))
            throw new IllegalArgumentException("Transaction exceeds the configured protocol profile");
        return transaction;
    }

    @Override public void broadcastTransaction(Transaction transaction) throws ForeignBlockchainException {
        definition.require(BROADCAST);
        checkTransaction(transaction);
        super.broadcastTransaction(transaction);
    }
}
