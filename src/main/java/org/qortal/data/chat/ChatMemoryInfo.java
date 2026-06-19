package org.qortal.data.chat;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class ChatMemoryInfo {

    private long freeMemory;

    private long memoryInUse;

    private long totalMemory;

    private long maxMemory;

    private long chatMemory;

    public ChatMemoryInfo() {
    }

    public ChatMemoryInfo(long freeMemory, long memoryInUse, long totalMemory, long maxMemory, long chatMemory) {
        this.freeMemory = freeMemory;
        this.memoryInUse = memoryInUse;
        this.totalMemory = totalMemory;
        this.maxMemory = maxMemory;
        this.chatMemory = chatMemory;
    }

    public long getFreeMemory() {
        return freeMemory;
    }

    public long getMemoryInUse() {
        return memoryInUse;
    }

    public long getTotalMemory() {
        return totalMemory;
    }

    public long getMaxMemory() {
        return maxMemory;
    }

    public long getChatMemory() {
        return chatMemory;
    }
}
