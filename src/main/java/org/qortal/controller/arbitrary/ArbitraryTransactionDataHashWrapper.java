package org.qortal.controller.arbitrary;

import org.qortal.arbitrary.misc.Service;
import org.qortal.data.transaction.ArbitraryTransactionData;

import java.util.Objects;

public class ArbitraryTransactionDataHashWrapper {

    private ArbitraryTransactionData data;

    private int service;

    private String name;

    private String identifier;

    public ArbitraryTransactionDataHashWrapper(ArbitraryTransactionData data) {
        this.data = data;

        this.service = data.getService().value;
        this.name = data.getName();
        this.identifier = data.getIdentifier();
    }

    public ArbitraryTransactionDataHashWrapper(int service, String name, String identifier) {
        this.service = service;
        this.name = name;
        this.identifier = identifier;
    }

    public ArbitraryTransactionData getData() {
        return data;
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
