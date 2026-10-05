package org.qortal.crosschain;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class SimpleTransaction {
    private String txHash;
    private Long timestamp;
    private long totalAmount;
    private long feeAmount;
    private List<Input> inputs;
    private List<Output> outputs;
    private String memo;


    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Input {
        private String address;
        private long amount;
        private boolean addressInWallet;

        public Input() {
        }

        public Input(String address, long amount, boolean addressInWallet) {
            this.address = address;
            this.amount = amount;
            this.addressInWallet = addressInWallet;
        }

        public String getAddress() {
            return address;
        }

        public long getAmount() {
            return amount;
        }

        public boolean getAddressInWallet() {
            return addressInWallet;
        }
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Output {
        private String address;
        private long amount;
        private boolean addressInWallet;

        public Output() {
        }

        public Output(String address, long amount, boolean addressInWallet) {
            this.address = address;
            this.amount = amount;
            this.addressInWallet = addressInWallet;
        }

        public String getAddress() {
            return address;
        }

        public long getAmount() {
            return amount;
        }

        public boolean getAddressInWallet() {
            return addressInWallet;
        }
    }


    public SimpleTransaction() {
    }

    public SimpleTransaction(String txHash, Long timestamp, long totalAmount, long feeAmount, List<Input> inputs, List<Output> outputs, String memo) {
        this.txHash = txHash;
        this.timestamp = timestamp;
        this.totalAmount = totalAmount;
        this.feeAmount = feeAmount;
        this.inputs = inputs;
        this.outputs = outputs;
        this.memo = memo;
    }

    public String getTxHash() {
        return txHash;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public long getFeeAmount() {
        return feeAmount;
    }

    public List<Input> getInputs() {
        return this.inputs;
    }

    public List<Output> getOutputs() {
        return this.outputs;
    }

    public String getMemo() {
        return this.memo;
    }
}
