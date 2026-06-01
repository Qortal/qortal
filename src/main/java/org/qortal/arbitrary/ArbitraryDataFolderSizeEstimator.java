package org.qortal.arbitrary;

import java.util.concurrent.atomic.AtomicLong;

public class ArbitraryDataFolderSizeEstimator {

    private AtomicLong size = new AtomicLong(0);

    private ArbitraryDataFolderSizeEstimator() {

    }

    private static ArbitraryDataFolderSizeEstimator instance = new ArbitraryDataFolderSizeEstimator();

    public static ArbitraryDataFolderSizeEstimator getInstance() {

        return instance;
    }

    public long get() {
        return this.size.get();
    }

    public void set( long size ) {
        this.size.set(size);
    }

    public void add( long size ) {
        this.size.addAndGet(size);
    }

    public void subtract( long size ) {
        this.size.addAndGet(-size);
    }
}
