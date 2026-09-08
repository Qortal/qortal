package org.qortal.crosschain;

import com.google.common.hash.HashCode;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.*;
import org.bitcoinj.script.ScriptBuilder;
import java.util.*;

/** Network data for client-side signing. No private-key or transaction-signing API. */
public final class LocalWalletSupport {
    private LocalWalletSupport() {}
    private static final Map<String, String> CHAINS = Map.of(
        "BTC", "000000000019d6689c085ae165831e93",
        "LTC", "12a765e31ffd4059bada1e25190f6e98",
        "DOGE", "1a91e3dace36e2be3bf030a65679fe82",
        "DGB", "7497ea1b465eb39f1c8f507bc877078f",
        "RVN", "0000006b444bc2f2ffe627be9d9e7e7a");

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
        DeterministicKey root = checkPublicKey(coin, xpub, expected);
        List<Map<String, Object>> outputs = new ArrayList<>();
        Map<String, String> previous = new LinkedHashMap<>();
        Map<String, Transaction> parsed = new HashMap<>();
        int bytes = 0;
        Integer tipHeight = null;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        // Match bitcoinj's non-hardened receive/change branches, including used change addresses.
        for (int branch = 0; branch < 2; branch++) {
            DeterministicKey parent = HDKeyDerivation.deriveChildKey(root, new ChildNumber(branch, false));
            int unused = 0;
            for (int index = 0; unused < 20; index++) {
                if (index >= 2000 || System.nanoTime() > deadline)
                    throw new ForeignBlockchainException("Wallet discovery limit reached");
                DeterministicKey key = HDKeyDerivation.deriveChildKey(parent, new ChildNumber(index, false));
                String address = LegacyAddress.fromKey(coin.params, key).toString();
                byte[] script = ScriptBuilder.createOutputScript(LegacyAddress.fromKey(coin.params, key)).getProgram();
                // Include mempool history so an unconfirmed used address cannot terminate discovery.
                boolean used = !coin.blockchainProvider.getAddressTransactions(script, true).isEmpty();
                unused = used ? 0 : unused + 1;
                // History includes mempool transactions. An unused address has no
                // spendable outputs: avoid a second network round trip for every gap key.
                if (!used) continue;
                for (UnspentOutput utxo : coin.blockchainProvider.getUnspentOutputs(script, false)) {
                    if (utxo.height <= 0) continue;
                    if (outputs.size() >= 1000) throw new ForeignBlockchainException("Too many wallet outputs");
                    String hash = HashCode.fromBytes(utxo.hash).toString();
                    Transaction tx = parsed.get(hash);
                    if (tx == null) {
                        byte[] raw = coin.blockchainProvider.getRawTransaction(utxo.hash);
                        bytes = Math.addExact(bytes, raw.length);
                        if (raw.length > 1000000 || bytes > 8000000)
                            throw new ForeignBlockchainException("Wallet response too large");
                        tx = new Transaction(coin.params, raw);
                        if (!tx.getTxId().toString().equals(hash))
                            throw new ForeignBlockchainException("Previous transaction hash mismatch");
                        parsed.put(hash, tx);
                        previous.put(hash, HashCode.fromBytes(raw).toString());
                    }
                    if (utxo.index < 0 || utxo.index >= tx.getOutputs().size())
                        throw new ForeignBlockchainException("Invalid previous output");
                    TransactionOutput out = tx.getOutput(utxo.index);
                    if (out.getValue().value != utxo.value || !Arrays.equals(script, out.getScriptBytes()))
                        throw new ForeignBlockchainException("Previous output mismatch");
                    // Newly mined outputs must mature before spending.
                    if (tx.isCoinBase()) {
                        if (tipHeight == null) tipHeight = coin.getBlockchainHeight();
                        if (tipHeight - utxo.height + 1 < coin.params.getSpendableCoinbaseDepth())
                            continue;
                    }
                    outputs.add(Map.of("address", address, "height", utxo.height,
                        "path", List.of(branch, index), "pathAsString", "M/" + branch + "/" + index, "scriptPubKeyHex", HashCode.fromBytes(script).toString(),
                        "txHash", hash, "outputIndex", utxo.index, "value", Long.toString(utxo.value)));
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", 1);
        result.put("blockchain", Map.of("BTC", "BITCOIN", "LTC", "LITECOIN", "DOGE", "DOGECOIN", "DGB", "DIGIBYTE", "RVN", "RAVENCOIN").get(coin.currencyCode));
        result.put("currencyCode", coin.currencyCode);
        result.put("activeNetwork", "MAIN");
        result.put("chainId", expected);
        result.put("tipHeight", tipHeight != null ? tipHeight : coin.getBlockchainHeight());
        result.put("confirmedOnly", true);
        result.put("transactionFormat", "LEGACY");
        result.put("transactionVersion", 1);
        result.put("sighashType", 1);
        result.put("sequence", 0xffffffffL);
        result.put("lockTime", 0L);
        result.put("minimumNonDustOutput", Long.toString(coin.params.getMinNonDustOutput().value));
        result.put("recommendedFeePerByte", Long.toString(Math.max(1, (coin.getFeePerKb().value + 999) / 1000)));
        Set<String> referenced = new HashSet<>();
        for (Map<String, Object> output : outputs) referenced.add((String) output.get("txHash"));
        previous.keySet().retainAll(referenced);
        result.put("previousTransactions", previous);
        result.put("utxos", outputs);
        return result;
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
}
