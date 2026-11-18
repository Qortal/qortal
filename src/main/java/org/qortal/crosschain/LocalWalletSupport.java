package org.qortal.crosschain;

import com.google.common.hash.HashCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.*;
import org.bitcoinj.script.ScriptBuilder;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Network data for client-side signing. No private-key or transaction-signing API. */
public final class LocalWalletSupport {
    private LocalWalletSupport() {}
    private static final Logger LOGGER = LogManager.getLogger(LocalWalletSupport.class);
    private static final Map<String, String> CHAINS = Map.of(
        "BTC", "000000000019d6689c085ae165831e93",
        "LTC", "12a765e31ffd4059bada1e25190f6e98",
        "DOGE", "1a91e3dace36e2be3bf030a65679fe82",
        "DGB", "7497ea1b465eb39f1c8f507bc877078f",
        "RVN", "0000006b444bc2f2ffe627be9d9e7e7a");
    // altcoinj's NetworkParameters values are stale for several supported
    // chains. These are the current default minimums for standard P2PKH
    // outputs; P2PKH is also the script type used for Hub wallet change.
    private static final Map<String, Long> MINIMUM_P2PKH_OUTPUTS = Map.of(
        "BTC", 546L,
        "LTC", 5_460L,
        "DOGE", 1_000_000L,
        "DGB", 5_460L,
        "RVN", 546L);

    public static long minimumP2pkhOutput(String currencyCode) {
        Long value = currencyCode == null ? null : MINIMUM_P2PKH_OUTPUTS.get(currencyCode);
        if (value == null)
            throw new IllegalArgumentException("Unsupported foreign currency");
        return value;
    }

    public static void checkChain(Bitcoiny coin, String expected) {
        if (coin == null || !CHAINS.containsKey(coin.currencyCode) || !coin.blockchainProvider.getNetId().endsWith("-MAIN")
                || !Objects.equals("bip122:" + CHAINS.get(coin.currencyCode), expected))
            throw new IllegalArgumentException("Unsupported or mismatched foreign network");
        Context.propagate(coin.bitcoinjContext);
    }

    public static DeterministicKey checkPublicKey(Bitcoiny coin, String xpub, String expected) {
        checkChain(coin, expected);
        if (xpub == null || xpub.length() > 128) throw new IllegalArgumentException("Invalid public key");
        DeterministicKey root = DeterministicKey.deserializeB58(null, xpub, coin.params);
        if (root.hasPrivKey() || root.getDepth() != 0 || root.getParentFingerprint() != 0 || root.getChildNumber().i() != 0)
            throw new IllegalArgumentException("Expected root public key");
        return root;
    }

    public static Map<String, Object> spendContext(Bitcoiny coin, String xpub, String expected)
            throws ForeignBlockchainException {
        return spendContext(coin, xpub, expected, System::nanoTime);
    }

    static Map<String, Object> spendContext(Bitcoiny coin, String xpub, String expected, LongSupplier clock)
            throws ForeignBlockchainException {
        PreparationTrace trace = new PreparationTrace(clock);
        try {
            return discoverSpendContext(coin, xpub, expected, trace);
        } catch (ForeignBlockchainException | RuntimeException e) {
            trace.failed(coin, e);
            throw e;
        }
    }

    private static Map<String, Object> discoverSpendContext(Bitcoiny coin, String xpub, String expected, PreparationTrace trace)
            throws ForeignBlockchainException {
        DeterministicKey root = checkPublicKey(coin, xpub, expected);
        List<Map<String, Object>> outputs = new ArrayList<>();
        Map<String, String> previous = new LinkedHashMap<>();
        Map<String, Transaction> parsed = new HashMap<>();
        int bytes = 0;
        Integer tipHeight = null;
        long deadline = trace.clock.getAsLong() + TimeUnit.SECONDS.toNanos(60);
        // Match bitcoinj's non-hardened receive/change branches, including used change addresses.
        for (int branch = 0; branch < 2; branch++) {
            DeterministicKey parent = HDKeyDerivation.deriveChildKey(root, new ChildNumber(branch, false));
            int unused = 0;
            for (int index = 0; unused < 20; index++) {
                trace.stage = "ADDRESS_DISCOVERY";
                if (index >= 2000)
                    throw trace.reject("DISCOVERY_KEY_LIMIT", "Wallet discovery limit reached");
                if (trace.clock.getAsLong() > deadline)
                    throw trace.reject("DISCOVERY_TIME_LIMIT", "Wallet discovery limit reached");
                DeterministicKey key = HDKeyDerivation.deriveChildKey(parent, new ChildNumber(index, false));
                String address = LegacyAddress.fromKey(coin.params, key).toString();
                byte[] script = ScriptBuilder.createOutputScript(LegacyAddress.fromKey(coin.params, key)).getProgram();
                // Include mempool history so an unconfirmed used address cannot terminate discovery.
                trace.stage = "ADDRESS_HISTORY";
                trace.addresses++;
                boolean used = !coin.blockchainProvider.getAddressTransactions(script, true).isEmpty();
                unused = used ? 0 : unused + 1;
                // History includes mempool transactions. An unused address has no
                // spendable outputs: avoid a second network round trip for every gap key.
                if (!used) continue;
                trace.stage = "UNSPENT_OUTPUTS";
                for (UnspentOutput utxo : coin.blockchainProvider.getUnspentOutputs(script, false)) {
                    if (utxo.height <= 0) continue;
                    if (outputs.size() >= 1000) throw trace.reject("OUTPUT_LIMIT", "Too many wallet outputs");
                    String hash = HashCode.fromBytes(utxo.hash).toString();
                    Transaction tx = parsed.get(hash);
                    if (tx == null) {
                        trace.stage = "PREVIOUS_TRANSACTION";
                        byte[] raw = coin.blockchainProvider.getRawTransaction(utxo.hash);
                        bytes = Math.addExact(bytes, raw.length);
                        trace.responseBytes = bytes;
                        if (raw.length > 1000000 || bytes > 8000000)
                            throw trace.reject("PREVIOUS_TRANSACTION_SIZE_LIMIT", "Wallet response too large");
                        trace.stage = "PREVIOUS_TRANSACTION_VALIDATION";
                        tx = coin.deserializeTransaction(raw);
                        if (!tx.getTxId().toString().equals(hash))
                            throw trace.reject("PREVIOUS_TRANSACTION_HASH_MISMATCH", "Previous transaction hash mismatch");
                        parsed.put(hash, tx);
                        previous.put(hash, HashCode.fromBytes(raw).toString());
                    }
                    trace.stage = "PREVIOUS_OUTPUT_VALIDATION";
                    if (utxo.index < 0 || utxo.index >= tx.getOutputs().size())
                        throw trace.reject("PREVIOUS_OUTPUT_INDEX_INVALID", "Invalid previous output");
                    TransactionOutput out = tx.getOutput(utxo.index);
                    if (out.getValue().value != utxo.value)
                        throw trace.reject("PREVIOUS_OUTPUT_VALUE_MISMATCH", "Previous output mismatch");
                    if (!Arrays.equals(script, out.getScriptBytes()))
                        throw trace.reject("PREVIOUS_OUTPUT_SCRIPT_MISMATCH", "Previous output mismatch");
                    // Newly mined outputs must mature before spending.
                    if (tx.isCoinBase()) {
                        trace.stage = "CHAIN_HEIGHT";
                        if (tipHeight == null) tipHeight = coin.getBlockchainHeight();
                        if (tipHeight - utxo.height + 1 < coin.params.getSpendableCoinbaseDepth())
                            continue;
                    }
                    outputs.add(Map.of("address", address, "height", utxo.height,
                        "path", List.of(branch, index), "pathAsString", "M/" + branch + "/" + index, "scriptPubKeyHex", HashCode.fromBytes(script).toString(),
                        "txHash", hash, "outputIndex", utxo.index, "value", Long.toString(utxo.value)));
                    trace.outputs = outputs.size();
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", 1);
        result.put("blockchain", Map.of("BTC", "BITCOIN", "LTC", "LITECOIN", "DOGE", "DOGECOIN", "DGB", "DIGIBYTE", "RVN", "RAVENCOIN").get(coin.currencyCode));
        result.put("currencyCode", coin.currencyCode);
        result.put("activeNetwork", "MAIN");
        result.put("chainId", expected);
        trace.stage = "CHAIN_HEIGHT";
        result.put("tipHeight", tipHeight != null ? tipHeight : coin.getBlockchainHeight());
        trace.stage = "BUILD_CONTEXT";
        result.put("confirmedOnly", true);
        result.put("transactionFormat", "LEGACY");
        result.put("transactionVersion", 1);
        result.put("sighashType", 1);
        result.put("sequence", 0xffffffffL);
        result.put("lockTime", 0L);
        result.put("minimumNonDustOutput", Long.toString(minimumP2pkhOutput(coin.currencyCode)));
        result.put("recommendedFeePerByte", Long.toString(Math.max(1, (coin.getFeePerKb().value + 999) / 1000)));
        Set<String> referenced = new HashSet<>();
        for (Map<String, Object> output : outputs) referenced.add((String) output.get("txHash"));
        previous.keySet().retainAll(referenced);
        result.put("previousTransactions", previous);
        result.put("utxos", outputs);
        return result;
    }

    /** Fixed diagnostic fields only: never log the request, provider message or throwable. */
    private static final class PreparationTrace {
        private final LongSupplier clock;
        private final long started;
        private String stage = "VALIDATE_WALLET";
        private String reason;
        private int addresses;
        private int outputs;
        private int responseBytes;

        private PreparationTrace(LongSupplier clock) {
            this.clock = clock;
            this.started = clock.getAsLong();
        }

        private ForeignBlockchainException reject(String reason, String message) {
            this.reason = reason;
            return new ForeignBlockchainException(message);
        }

        private void failed(Bitcoiny coin, Exception failure) {
            Integer daemonCode = null;
            if (reason == null) {
                if (failure instanceof ForeignBlockchainException.NetworkException) reason = "ELECTRUM_NETWORK";
                else if (failure instanceof ForeignBlockchainException.NotFoundException) reason = "PREVIOUS_TRANSACTION_NOT_FOUND";
                else if (failure instanceof ForeignBlockchainException) reason = "PROVIDER_RESPONSE_REJECTED";
                else reason = stage.equals("VALIDATE_WALLET") ? "INVALID_REQUEST" : "INVALID_RESPONSE";
            }
            if (failure instanceof ForeignBlockchainException.NetworkException)
                daemonCode = ((ForeignBlockchainException.NetworkException) failure).getDaemonErrorCode();
            String currency = coin != null && CHAINS.containsKey(coin.currencyCode) ? coin.currencyCode : "UNKNOWN";
            LOGGER.warn("[local-wallet-prepare] coin={} stage={} reason={} addresses={} outputs={} responseBytes={} elapsedMs={} cause={} daemonCode={}",
                    currency, stage, reason, addresses, outputs, responseBytes,
                    TimeUnit.NANOSECONDS.toMillis(Math.max(0, clock.getAsLong() - started)), failure.getClass().getSimpleName(), daemonCode);
        }
    }

    public static String broadcast(Bitcoiny coin, String hex, String expected) throws ForeignBlockchainException {
        checkChain(coin, expected);
        if (hex == null || hex.length() > 2000000 || !hex.matches("(?:[0-9a-fA-F]{2})+"))
            throw new IllegalArgumentException("Invalid raw transaction");
        byte[] raw = HashCode.fromString(hex.toLowerCase(Locale.ROOT)).asBytes();
        Transaction tx = new Transaction(coin.params, raw);
        tx.verify();
        if (tx.isCoinBase() || !Arrays.equals(raw, tx.bitcoinSerialize()))
            throw new IllegalArgumentException("Invalid spend transaction");
        coin.blockchainProvider.broadcastTransaction(raw, tx.getTxId().toString());
        return tx.getTxId().toString();
    }

    public static Map<String, Object> transactionStatus(Bitcoiny coin, String txId, String expected)
            throws ForeignBlockchainException {
        checkChain(coin, expected);
        if (txId == null || !txId.matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("Invalid transaction ID");

        String normalizedTxId = txId.toLowerCase(Locale.ROOT);
        BitcoinyTransaction transaction;
        try {
            transaction = coin.blockchainProvider.getTransaction(normalizedTxId);
        } catch (ForeignBlockchainException.NotFoundException e) {
            transaction = null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", 1);
        result.put("currencyCode", coin.currencyCode);
        result.put("activeNetwork", "MAIN");
        result.put("chainId", expected);
        result.put("txId", normalizedTxId);
        if (transaction == null) {
            result.put("status", "UNKNOWN");
        } else {
            if (!normalizedTxId.equalsIgnoreCase(transaction.txHash))
                throw new ForeignBlockchainException("Transaction ID mismatch");
            result.put("status", transaction.timestamp == null ? "MEMPOOL" : "CONFIRMED");
            if (transaction.timestamp != null)
                result.put("timestamp", transaction.timestamp);
        }
        return result;
    }
}
