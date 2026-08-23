package org.qortal.data.system;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class StorageInfo {

    private long usedBytes;
    private Long capacityBytes;

    public StorageInfo() {}

    public StorageInfo(long usedBytes, Long capacityBytes) {
        this.usedBytes = usedBytes;
        this.capacityBytes = capacityBytes;
    }

    public long getUsedBytes() { return usedBytes; }
    public Long getCapacityBytes() { return capacityBytes; }
}
