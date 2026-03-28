package org.qortal.controller.arbitrary;

import org.qortal.arbitrary.misc.Service;
import org.qortal.data.transaction.ArbitraryTransactionData;

import java.util.Objects;

public class ArbitraryTransactionDataHashWrapper {

    private ArbitraryTransactionData data;

    private byte[] signature;

    private int service;

    private String name;

    private String identifier;

    private byte[] metadataHash;

    private long timestamp;

    public ArbitraryTransactionDataHashWrapper(ArbitraryTransactionData data) {
        this.data = data;
        this.signature = data.getSignature();
        this.service = data.getService().value;
        this.name = data.getName();
        this.identifier = data.getIdentifier();
        this.metadataHash = data.getMetadataHash();
        this.timestamp = data.getTimestamp();
    }

    public ArbitraryTransactionDataHashWrapper(int service, String name, String identifier) {
        this.service = service;
        this.name = name;
        this.identifier = identifier;
    }

    /** Lightweight constructor — stores only what is needed for dedup, filtering and event dispatch. */
    public ArbitraryTransactionDataHashWrapper(byte[] signature, int service, String name, String identifier,
                                               byte[] metadataHash, long timestamp) {
        this.signature = signature;
        this.service = service;
        this.name = name;
        this.identifier = identifier;
        this.metadataHash = metadataHash;
        this.timestamp = timestamp;
    }

    public ArbitraryTransactionData getData() {
        return data;
    }

    public byte[] getSignature() {
        return signature;
    }

    public int getService() {
        return service;
    }

    public String getName() {
        return name;
    }

    public String getIdentifier() {
        return identifier;
    }

    public byte[] getMetadataHash() {
        return metadataHash;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ArbitraryTransactionDataHashWrapper that = (ArbitraryTransactionDataHashWrapper) o;
        return service == that.service && name.equals(that.name) && Objects.equals(identifier, that.identifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(service, name, identifier);
    }
}
