package org.qortal.data.group;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

// All properties to be converted to JSON via JAX-RS
@XmlAccessorType(XmlAccessType.FIELD)
public class GroupMemberTransactionCounterData {

    private int count;
    private String address;
    private String name;

    // Constructors

    // necessary for JAX-RS serialization
    protected GroupMemberTransactionCounterData() {
    }

    public GroupMemberTransactionCounterData(int count, String address, String name) {
        this.count = count;
        this.address = address;
        this.name = name;
    }

    // Getters / setters


    public int getCount() {
        return count;
    }

    public String getAddress() {
        return address;
    }

    public String getName() {
        return name;
    }
}
