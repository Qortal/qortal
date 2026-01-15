package org.qortal.controller.hsqldb;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.account.BlockHeightRange;
import org.qortal.data.account.BlockHeightRangeAddressAmounts;
import org.qortal.repository.hsqldb.HSQLDBCacheUtils;
import org.qortal.settings.Settings;
import org.qortal.utils.BalanceRecorderUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class HSQLDBBalanceRecorder extends Thread {

    private static final Logger LOGGER = LogManager.getLogger(HSQLDBBalanceRecorder.class);

    private static volatile HSQLDBBalanceRecorder SINGLETON = null;

    private final ConcurrentHashMap<Integer, List<AccountBalanceData>> balancesByHeight = new ConcurrentHashMap<>();

    private final CopyOnWriteArrayList<BlockHeightRangeAddressAmounts> balanceDynamics = new CopyOnWriteArrayList<>();

    private final int priorityRequested;
    private final int frequency;
    private final int capacity;

    private volatile boolean running = true;

    private HSQLDBBalanceRecorder( int priorityRequested, int frequency, int capacity) {

        super("Balance Recorder");

        this.priorityRequested = priorityRequested;
        this.frequency = frequency;
        this.capacity = capacity;

        if (priorityRequested >= Thread.MIN_PRIORITY && priorityRequested <= Thread.MAX_PRIORITY) {
            this.setPriority(priorityRequested);
        }
    }

    public static HSQLDBBalanceRecorder getInstance() {

        if (SINGLETON == null) {
            synchronized (HSQLDBBalanceRecorder.class) {
                if (SINGLETON == null) {
                    SINGLETON
                            = new HSQLDBBalanceRecorder(
                            Settings.getInstance().getBalanceRecorderPriority(),
                            Settings.getInstance().getBalanceRecorderFrequency(),
                            Settings.getInstance().getBalanceRecorderCapacity()
                    );

                    LOGGER.info("Instantiating HSQLDBBalanceRecorder...");
                }
            }
        }

        return SINGLETON;
    }

    @Override
    public void run() {

        HSQLDBCacheUtils.startRecordingBalances(this.balancesByHeight, this.balanceDynamics, this.priorityRequested, this.frequency, this.capacity);
    }

    public List<BlockHeightRangeAddressAmounts> getLatestDynamics(int limit, long offset) {

        return this.balanceDynamics.stream()
                .sorted(BalanceRecorderUtils.BLOCK_HEIGHT_RANGE_ADDRESS_AMOUNTS_COMPARATOR.reversed())
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<BlockHeightRange> getRanges(Integer offset, Integer limit, Boolean reverse) {

        Comparator<BlockHeightRange> comparator = reverse
                ? BalanceRecorderUtils.BLOCK_HEIGHT_RANGE_COMPARATOR.reversed()
                : BalanceRecorderUtils.BLOCK_HEIGHT_RANGE_COMPARATOR;

        return this.balanceDynamics.stream()
                .map(BlockHeightRangeAddressAmounts::getRange)
                .sorted(comparator)
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());
    }

    public Optional<BlockHeightRangeAddressAmounts> getAddressAmounts(BlockHeightRange range) {

        return this.balanceDynamics.stream()
            .filter( dynamic -> dynamic.getRange().equals(range))
            .findAny();
    }

    public Optional<BlockHeightRange> getRange( int height ) {
        return this.balanceDynamics.stream()
            .map(BlockHeightRangeAddressAmounts::getRange)
            .filter( range -> range.getBegin() < height && range.getEnd() >= height )
            .findAny();
    }

    private Optional<Integer> getLastHeight() {
        return this.balancesByHeight.keySet().stream().max(Comparator.naturalOrder());
    }

    public List<Integer> getBlocksRecorded() {

        return new ArrayList<>(this.balancesByHeight.keySet());
    }

    public void shutdown() {
        this.running = false;
        this.interrupt();
    }

    @Override
    public String toString() {
        return "HSQLDBBalanceRecorder{" +
                "priorityRequested=" + priorityRequested +
                ", frequency=" + frequency +
                ", capacity=" + capacity +
                '}';
    }
}
