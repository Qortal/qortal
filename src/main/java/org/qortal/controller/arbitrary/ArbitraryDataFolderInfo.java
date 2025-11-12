package org.qortal.controller.arbitrary;

public class ArbitraryDataFolderInfo {

    private long totalSpace;

    private int chunkCount;

    public ArbitraryDataFolderInfo(long totalSpace, int chunkCount) {
        this.totalSpace = totalSpace;
        this.chunkCount = chunkCount;
    }

    public long getTotalSpace() {
        return totalSpace;
    }

    public int getChunkCount() {
        return chunkCount;
    }
}
