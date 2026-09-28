package org.qortal.crosschain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts the Unified wallet's local SQLite transaction projections into the
 * transaction model exposed by Qortal's cross-chain API.
 */
final class PirateTransactionHistory {

    private PirateTransactionHistory() {
    }

    static boolean hasUnresolvedRecipients(List<SimpleTransaction> transactions) {
        if (transactions == null) {
            return false;
        }
        for (SimpleTransaction transaction : transactions) {
            if (transaction == null || transaction.getOutputs() == null) {
                continue;
            }
            for (SimpleTransaction.Output output : transaction.getOutputs()) {
                if (output != null && "[UNKNOWN]".equals(output.getAddress())) {
                    return true;
                }
            }
        }
        return false;
    }

    static Set<String> internalAddresses(JSONArray incomingDepositsJson) {
        Set<String> addresses = new HashSet<>();
        for (int i = 0; i < incomingDepositsJson.length(); i++) {
            JSONObject deposit = incomingDepositsJson.optJSONObject(i);
            if (deposit == null || !"internal".equalsIgnoreCase(deposit.optString("address_scope"))) {
                continue;
            }
            String address = deposit.optString("address", null);
            if (address != null && !address.isBlank()) {
                addresses.add(address);
            }
        }
        return addresses;
    }

    static RecoveredRecipients parseTransactionDetails(JSONObject details, Set<String> internalAddresses,
            String myAddress) throws ForeignBlockchainException {
        String txId = requireString(details, "txid", "get_transaction_details");
        JSONArray recipientsJson = details.optJSONArray("recipients");
        if (recipientsJson == null) {
            throw new ForeignBlockchainException(
                    "Pirate Unified get_transaction_details omitted recipients");
        }

        List<SimpleTransaction.Output> outputs = new ArrayList<>();
        String memo = details.isNull("memo") ? null : details.optString("memo", null);
        for (int i = 0; i < recipientsJson.length(); i++) {
            JSONObject recipient = recipientsJson.optJSONObject(i);
            if (recipient == null) {
                continue;
            }
            String address = requireString(recipient, "address", "get_transaction_details");
            long amount = requireLong(recipient, "amount", "get_transaction_details");
            if (internalAddresses != null && internalAddresses.contains(address)) {
                continue;
            }
            outputs.add(new SimpleTransaction.Output(address, amount, address.equals(myAddress)));
            if (memo == null && !recipient.isNull("memo")) {
                memo = recipient.optString("memo", null);
            }
        }
        return new RecoveredRecipients(txId, outputs, memo);
    }

    static List<SimpleTransaction> overlayRecoveredRecipients(List<SimpleTransaction> transactions,
            Map<String, RecoveredRecipients> recoveredByTransaction) {
        if (transactions == null || transactions.isEmpty() || recoveredByTransaction.isEmpty()) {
            return transactions == null ? Collections.emptyList() : new ArrayList<>(transactions);
        }

        List<SimpleTransaction> enriched = new ArrayList<>(transactions.size());
        for (SimpleTransaction transaction : transactions) {
            RecoveredRecipients recovered = recoveredByTransaction.get(transaction.getTxHash());
            if (recovered == null || recovered.outputs.isEmpty()
                    || !hasUnresolvedRecipients(Collections.singletonList(transaction))) {
                enriched.add(transaction);
                continue;
            }

            List<SimpleTransaction.Output> outputs = new ArrayList<>();
            if (transaction.getOutputs() != null) {
                for (SimpleTransaction.Output output : transaction.getOutputs()) {
                    if (output != null && !"[UNKNOWN]".equals(output.getAddress())) {
                        outputs.add(output);
                    }
                }
            }
            outputs.addAll(recovered.outputs);
            enriched.add(new SimpleTransaction(
                    transaction.getTxHash(),
                    transaction.getTimestamp(),
                    transaction.getTotalAmount(),
                    transaction.getFeeAmount(),
                    transaction.getInputs(),
                    outputs,
                    recovered.memo != null ? recovered.memo : transaction.getMemo()));
        }
        return enriched;
    }

    static final class RecoveredRecipients {
        private final String txId;
        private final List<SimpleTransaction.Output> outputs;
        private final String memo;

        RecoveredRecipients(String txId, List<SimpleTransaction.Output> outputs, String memo) {
            this.txId = txId;
            this.outputs = List.copyOf(outputs);
            this.memo = memo;
        }

        String getTxId() {
            return this.txId;
        }

        List<SimpleTransaction.Output> getOutputs() {
            return this.outputs;
        }
    }

    static List<SimpleTransaction> parseQortal(JSONArray transactionsJson, String myAddress,
            long defaultFee) throws ForeignBlockchainException {
        List<SimpleTransaction> transactions = new ArrayList<>();
        for (int i = 0; i < transactionsJson.length(); i++) {
            JSONObject transactionJson = transactionsJson.getJSONObject(i);
            if (!transactionJson.has("txid")) {
                continue;
            }

            String txId = transactionJson.getString("txid");
            long timestamp = transactionJson.getLong("datetime");
            boolean hasAmount = transactionJson.has("amount");
            long amount = transactionJson.optLong("amount", 0L);
            boolean hasFee = transactionJson.has("fee");
            long fee = transactionJson.optLong("fee", 0L);
            String memo = null;
            List<SimpleTransaction.Input> inputs = new ArrayList<>();
            List<SimpleTransaction.Output> outputs = new ArrayList<>();
            long computedAmount = 0L;
            int outgoingCount = 0;

            JSONArray incomingMetadatas = transactionJson.optJSONArray("incoming_metadata");
            JSONArray outgoingMetadatas = transactionJson.optJSONArray("outgoing_metadata");
            boolean hasIncoming = incomingMetadatas != null && incomingMetadatas.length() > 0;
            boolean hasOutgoing = outgoingMetadatas != null && outgoingMetadatas.length() > 0;
            if (!hasIncoming && !hasOutgoing) {
                incomingMetadatas = transactionJson.optJSONArray("incoming_metadata_change");
                outgoingMetadatas = transactionJson.optJSONArray("outgoing_metadata_change");
            }

            if (incomingMetadatas != null) {
                for (int j = 0; j < incomingMetadatas.length(); j++) {
                    JSONObject incomingMetadata = incomingMetadatas.getJSONObject(j);
                    if (incomingMetadata.has("value")) {
                        long value = incomingMetadata.getLong("value");
                        computedAmount += value;
                        if (incomingMetadata.has("address")) {
                            inputs.add(new SimpleTransaction.Input("[PRIVATE]", value, false));
                            String address = incomingMetadata.getString("address");
                            outputs.add(new SimpleTransaction.Output(address, value, address.equals(myAddress)));
                        }
                    }
                    if (incomingMetadata.has("memo") && !incomingMetadata.isNull("memo")) {
                        memo = incomingMetadata.getString("memo");
                    }
                }
            }

            if (outgoingMetadatas != null) {
                for (int j = 0; j < outgoingMetadatas.length(); j++) {
                    JSONObject outgoingMetadata = outgoingMetadatas.getJSONObject(j);
                    if (outgoingMetadata.has("value")) {
                        long value = outgoingMetadata.getLong("value");
                        computedAmount -= value;
                        outgoingCount++;
                        if (outgoingMetadata.has("address")) {
                            inputs.add(new SimpleTransaction.Input(myAddress, value, true));
                            String address = outgoingMetadata.getString("address");
                            outputs.add(new SimpleTransaction.Output(address, value, address.equals(myAddress)));
                        }
                    }
                    if (outgoingMetadata.has("memo") && !outgoingMetadata.isNull("memo")) {
                        memo = outgoingMetadata.getString("memo");
                    }
                }
            }

            if (!hasAmount) {
                amount = computedAmount;
            }
            if (!hasFee && outgoingCount > 0) {
                fee = defaultFee * outgoingCount;
            }
            long timestampMillis;
            try {
                timestampMillis = Math.multiplyExact(timestamp, 1000L);
            } catch (ArithmeticException e) {
                throw new ForeignBlockchainException("Pirate transaction timestamp overflow");
            }
            transactions.add(new SimpleTransaction(txId, timestampMillis, amount, fee, inputs, outputs, memo));
        }
        return transactions;
    }

    static List<SimpleTransaction> parseUnified(JSONArray transactionsJson,
            JSONArray incomingDepositsJson, String myAddress) throws ForeignBlockchainException {
        Map<String, UnifiedTransactionAccumulator> transactionsById = new LinkedHashMap<>();
        for (int i = 0; i < transactionsJson.length(); i++) {
            JSONObject transactionJson = transactionsJson.getJSONObject(i);
            String txId = requireString(transactionJson, "txid", "list_transactions");
            long amount = requireLong(transactionJson, "amount", "list_transactions");
            long fee = requireLong(transactionJson, "fee", "list_transactions");
            long timestamp = requireLong(transactionJson, "timestamp", "list_transactions");
            UnifiedTransactionAccumulator transaction = transactionsById.computeIfAbsent(txId,
                    ignored -> new UnifiedTransactionAccumulator(txId));
            try {
                transaction.amount = Math.addExact(transaction.amount, amount);
                transaction.fee = Math.addExact(transaction.fee, fee);
                if (amount < 0) {
                    transaction.outgoingAmount = Math.addExact(transaction.outgoingAmount, Math.negateExact(amount));
                    transaction.hasOutgoing = true;
                } else if (amount > 0) {
                    transaction.hasIncoming = true;
                }
                transaction.timestamp = Math.max(transaction.timestamp, timestamp);
            } catch (ArithmeticException e) {
                throw new ForeignBlockchainException("Pirate Unified transaction amount overflow");
            }
            if (transaction.memo == null && transactionJson.has("memo") && !transactionJson.isNull("memo")) {
                transaction.memo = transactionJson.getString("memo");
            }
        }

        Map<String, List<JSONObject>> depositsByTransaction = new HashMap<>();
        for (int i = 0; i < incomingDepositsJson.length(); i++) {
            JSONObject deposit = incomingDepositsJson.getJSONObject(i);
            String txId = requireString(deposit, "txid", "list_incoming_deposits");
            depositsByTransaction.computeIfAbsent(txId, ignored -> new ArrayList<>()).add(deposit);
        }

        List<SimpleTransaction> transactions = new ArrayList<>();
        for (UnifiedTransactionAccumulator transaction : transactionsById.values()) {
            List<SimpleTransaction.Input> inputs = new ArrayList<>();
            List<SimpleTransaction.Output> outputs = new ArrayList<>();

            if (transaction.hasIncoming) {
                long attributedIncoming = 0L;
                for (JSONObject deposit : depositsByTransaction.getOrDefault(transaction.txId, Collections.emptyList())) {
                    if ("internal".equalsIgnoreCase(deposit.optString("address_scope"))) {
                        continue;
                    }
                    long value = requireLong(deposit, "value", "list_incoming_deposits");
                    String address = requireString(deposit, "address", "list_incoming_deposits");
                    inputs.add(new SimpleTransaction.Input("[PRIVATE]", value, false));
                    outputs.add(new SimpleTransaction.Output(address, value, true));
                    try {
                        attributedIncoming = Math.addExact(attributedIncoming, value);
                    } catch (ArithmeticException e) {
                        throw new ForeignBlockchainException("Pirate Unified incoming amount overflow");
                    }
                }
                if (attributedIncoming == 0L) {
                    long value = positiveMagnitude(transaction.amount);
                    inputs.add(new SimpleTransaction.Input("[PRIVATE]", value, false));
                    outputs.add(new SimpleTransaction.Output(myAddress, value, true));
                }
            }

            if (transaction.hasOutgoing) {
                inputs.add(new SimpleTransaction.Input(myAddress, transaction.outgoingAmount, true));
                outputs.add(new SimpleTransaction.Output("[UNKNOWN]", transaction.outgoingAmount, false));
            }

            if (inputs.isEmpty() && outputs.isEmpty()) {
                long displayAmount = positiveMagnitude(transaction.amount);
                inputs.add(new SimpleTransaction.Input("[PRIVATE]", displayAmount, false));
                outputs.add(new SimpleTransaction.Output(myAddress, displayAmount, true));
            }

            long timestampMillis;
            try {
                timestampMillis = Math.multiplyExact(transaction.timestamp, 1000L);
            } catch (ArithmeticException e) {
                throw new ForeignBlockchainException("Pirate Unified transaction timestamp overflow");
            }
            transactions.add(new SimpleTransaction(transaction.txId, timestampMillis, transaction.amount,
                    transaction.fee, inputs, outputs, transaction.memo));
        }

        return transactions;
    }

    private static long positiveMagnitude(long value) throws ForeignBlockchainException {
        try {
            return value < 0 ? Math.negateExact(value) : value;
        } catch (ArithmeticException e) {
            throw new ForeignBlockchainException("Pirate Unified transaction amount overflow");
        }
    }

    private static String requireString(JSONObject json, String field, String command)
            throws ForeignBlockchainException {
        String value = json.optString(field, null);
        if (value == null || value.isBlank()) {
            throw new ForeignBlockchainException("Pirate Unified " + command + " omitted " + field);
        }
        return value;
    }

    private static long requireLong(JSONObject json, String field, String command)
            throws ForeignBlockchainException {
        if (!json.has(field) || json.isNull(field)) {
            throw new ForeignBlockchainException("Pirate Unified " + command + " omitted " + field);
        }
        Object value = json.get(field);
        try {
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            throw new ForeignBlockchainException("Pirate Unified " + command + " returned invalid " + field);
        }
    }

    private static final class UnifiedTransactionAccumulator {
        private final String txId;
        private long timestamp;
        private long amount;
        private long fee;
        private long outgoingAmount;
        private boolean hasIncoming;
        private boolean hasOutgoing;
        private String memo;

        private UnifiedTransactionAccumulator(String txId) {
            this.txId = txId;
        }
    }
}
