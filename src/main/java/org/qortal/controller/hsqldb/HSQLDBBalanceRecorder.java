package org.qortal.controller.hsqldb;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.PropertySource;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.account.BlockHeightRange;
import org.qortal.data.account.BlockHeightRangeAddressAmounts;
import org.qortal.repository.hsqldb.HSQLDBCacheUtils;
import org.qortal.settings.Settings;
import org.qortal.utils.BalanceRecorderUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class HSQLDBBalanceRecorder extends Thread{

    private static final Logger LOGGER = LogManager.getLogger(HSQLDBBalanceRecorder.class);

    private static HSQLDBBalanceRecorder SINGLETON = null;

    private ConcurrentHashMap<Integer, List<AccountBalanceData>> balancesByHeight = new ConcurrentHashMap<>();

    private ConcurrentHashMap<String, List<AccountBalanceData>> balancesByAddress = new ConcurrentHashMap<>();

    private CopyOnWriteArrayList<BlockHeightRangeAddressAmounts> balanceDynamics = new CopyOnWriteArrayList<>();

    private int priorityRequested;
    private int frequency;
    private int capacity;

    private HSQLDBBalanceRecorder( int priorityRequested, int frequency, int capacity) {

        super("Balance Recorder");

        this.priorityRequested = priorityRequested;
        this.frequency = frequency;
        this.capacity = capacity;
    }

    public static Optional<HSQLDBBalanceRecorder> getInstance() {

        if( SINGLETON == null ) {
            SINGLETON
                = new HSQLDBBalanceRecorder(
                    Settings.getInstance().getBalanceRecorderPriority(),
                    Settings.getInstance().getBalanceRecorderFrequency(),
                    Settings.getInstance().getBalanceRecorderCapacity()
            );
        }

        return Optional.of(SINGLETON);
    }

    @Override
    public void run() {

        Thread.currentThread().setName("Balance Recorder");

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

        if( reverse ) {
            return this.balanceDynamics.stream()
                    .map(BlockHeightRangeAddressAmounts::getRange)
                    .sorted(BalanceRecorderUtils.BLOCK_HEIGHT_RANGE_COMPARATOR.reversed())
                    .skip(offset)
                    .limit(limit)
                    .collect(Collectors.toList());
        }
        else {
            return this.balanceDynamics.stream()
                    .map(BlockHeightRangeAddressAmounts::getRange)
                    .sorted(BalanceRecorderUtils.BLOCK_HEIGHT_RANGE_COMPARATOR)
                    .skip(offset)
                    .limit(limit)
                    .collect(Collectors.toList());
        }
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
        return this.balancesByHeight.keySet().stream().sorted(Comparator.reverseOrder()).findFirst();
    }

    public List<Integer> getBlocksRecorded() {

        return this.balancesByHeight.keySet().stream().collect(Collectors.toList());
    }

    public List<AccountBalanceData> getAccountBalanceRecordings(String address) {
        return this.balancesByAddress.get(address);
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
