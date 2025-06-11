package org.qortal.crosschain;

import org.apache.logging.log4j.LogManager;
import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;
import org.bouncycastle.util.encoders.Hex;
import org.libdohj.core.AltcoinNetworkParameters;
import org.libdohj.core.AltcoinSerializer;
import org.qortal.api.model.crosschain.BitcoinyTBDRequest;
import org.qortal.repository.DataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigInteger;

import static org.bitcoinj.core.Coin.COIN;

/**
 * Common parameters Bitcoin fork networks.
 */
public class DeterminedNetworkParams extends NetworkParameters implements AltcoinNetworkParameters {

    private static final org.apache.logging.log4j.Logger LOGGER = LogManager.getLogger(DeterminedNetworkParams.class);

    public static final long MAX_TARGET_COMPACT_BITS = 0x1e0fffffL;
    /**
     *  Standard format for the LITE denomination.
     */
    private MonetaryFormat fullUnit;

    /**
     * Standard format for the mLITE denomination.
     * */
    private MonetaryFormat mUnit;

    /**
     * Base Unit
     *
     * The equivalent for Satoshi for Bitcoin
     */
    private MonetaryFormat baseUnit;

     /**
     * The maximum money to be generated
     */
    public final Coin maxMoney;

    /**
     * Currency code for full unit.
     * */
    private String code = "LITE";

    /**
     *  Currency code for milli Unit.
     *  */
    private String mCode = "mLITE";

    /**
     * Currency code for base unit.
     * */
    private String baseCode = "Liteoshi";


    private int protocolVersionMinimum;
    private int protocolVersionCurrent;

    private static final Coin BASE_SUBSIDY = COIN.multiply(50);

    protected Logger log = LoggerFactory.getLogger(DeterminedNetworkParams.class);

    private int minNonDustOutput;

    private String uriScheme;

    private boolean hasMaxMoney;

    public DeterminedNetworkParams( BitcoinyTBDRequest request ) throws DataException {
        super();

        if( request.getTargetTimespan() > 0 && request.getTargetSpacing() > 0 )
            this.interval = request.getTargetTimespan() / request.getTargetSpacing();

        this.targetTimespan = request.getTargetTimespan();

        // this compact value is used for every Bitcoin fork for no documented reason
        this.maxTarget = Utils.decodeCompactBits(MAX_TARGET_COMPACT_BITS);

        this.packetMagic = request.getPacketMagic();

        this.id = request.getId();
        this.port = request.getPort();
        this.addressHeader = request.getAddressHeader();
        this.p2shHeader = request.getP2shHeader();
        this.segwitAddressHrp = request.getSegwitAddressHrp();

        this.dumpedPrivateKeyHeader = request.getDumpedPrivateKeyHeader();

        LOGGER.info( "Creating Genesis Block ...");

        // BitcoinJ v16 has a new native method for this
        //this.genesisBlock = CoinParamsUtil.createGenesisBlockFromRequest(this, request);

//        LOGGER.info("Created Genesis Block: genesisBlock = " + genesisBlock );

        // this is 100 for each coin from what I can tell
        this.spendableCoinbaseDepth = 100;

        this.subsidyDecreaseBlockCount = request.getSubsidyDecreaseBlockCount();

//        String genesisHash = genesisBlock.getHashAsString();
//
//        LOGGER.info("genesisHash = " + genesisHash);
//
//        LOGGER.info("request = " + request);
//
//        checkState(genesisHash.equals(request.getExpectedGenesisHash()))
//        alertSigningKey is removed in v16
//        this.alertSigningKey = Hex.decode(request.getPubKey());

        this.majorityEnforceBlockUpgrade = request.getMajorityEnforceBlockUpgrade();
        this.majorityRejectBlockOutdated = request.getMajorityRejectBlockOutdated();
        this.majorityWindow = request.getMajorityWindow();

        this.dnsSeeds = request.getDnsSeeds();

        this.bip32HeaderP2PKHpub = request.getBip32HeaderP2PKHpub();
        this.bip32HeaderP2PKHpriv = request.getBip32HeaderP2PKHpriv();

        this.code = request.getCode();
        this.mCode = request.getmCode();
        this.baseCode = request.getBaseCode();

        this.fullUnit = MonetaryFormat.BTC.noCode()
                .code(0, this.code)
                .code(3, this.mCode)
                .code(7, this.baseCode);
        this.mUnit = fullUnit.shift(3).minDecimals(2).optionalDecimals(2);
        this.baseUnit = fullUnit.shift(7).minDecimals(0).optionalDecimals(2);

        this.protocolVersionMinimum = request.getProtocolVersionMinimum();
        this.protocolVersionCurrent = request.getProtocolVersionCurrent();

        this.minNonDustOutput = request.getMinNonDustOutput();

        this.uriScheme = request.getUriScheme();

        this.hasMaxMoney = request.isHasMaxMoney();

        this.maxMoney = COIN.multiply(request.getMaxMoney());
    }

    @Override
    public Coin getBlockSubsidy(final int height) {
        // return BASE_SUBSIDY.shiftRight(height / getSubsidyDecreaseBlockCount());
        // return something concerning Digishield for Dogecoin
        // return something different for Digibyte validation.cpp::GetBlockSubsidy
        // we may not need to support this
        throw new UnsupportedOperationException();
    }

    /**
     * Get the hash to use for a block.
     */
    @Override
    public Sha256Hash getBlockDifficultyHash(Block block) {

        return ((AltcoinBlock) block).getScryptHash();
    }

    @Override
    public boolean isTestNet() {
        return false;
    }

    public MonetaryFormat getMonetaryFormat() {

        return this.fullUnit;
    }

    @Override
    public Coin getMaxMoney() {

        return this.maxMoney;
    }

    @Override
    public Coin getMinNonDustOutput() {

        return Coin.valueOf(this.minNonDustOutput);
    }

    @Override
    public String getUriScheme() {

        return this.uriScheme;
    }

    @Override
    public boolean hasMaxMoney() {

        return this.hasMaxMoney;
    }


    @Override
    public String getPaymentProtocolId() {
        return this.id;
    }

    @Override
    public void checkDifficultyTransitions(StoredBlock storedPrev, Block nextBlock, BlockStore blockStore)
        throws VerificationException, BlockStoreException {
        try {
            final long newTargetCompact = calculateNewDifficultyTarget(storedPrev, nextBlock, blockStore);
            final long receivedTargetCompact = nextBlock.getDifficultyTarget();

            if (newTargetCompact != receivedTargetCompact)
                throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                        newTargetCompact + " vs " + receivedTargetCompact);
        } catch (CheckpointEncounteredException ex) {
            // Just have to take it on trust then
        }
    }

    @Override
    public Block getGenesisBlock() {
        //ToDo: Finish
        return null;
    }

    /**
     * Get the difficulty target expected for the next block. This includes all
     * the weird cases for Litecoin such as testnet blocks which can be maximum
     * difficulty if the block interval is high enough.
     *
     * @throws CheckpointEncounteredException if a checkpoint is encountered while
     * calculating difficulty target, and therefore no conclusive answer can
     * be provided.
     */
    public long calculateNewDifficultyTarget(StoredBlock storedPrev, Block nextBlock, BlockStore blockStore)
        throws VerificationException, BlockStoreException, CheckpointEncounteredException {
        final Block prev = storedPrev.getHeader();
        final int previousHeight = storedPrev.getHeight();
        final int retargetInterval = this.getInterval();

        // Is this supposed to be a difficulty transition point?
        if ((storedPrev.getHeight() + 1) % retargetInterval != 0) {
            if (this.allowMinDifficultyBlocks()) {
                // Special difficulty rule for testnet:
                // If the new block's timestamp is more than 5 minutes
                // then allow mining of a min-difficulty block.
                if (nextBlock.getTimeSeconds() > prev.getTimeSeconds() + getTargetSpacing() * 2) {
                    return Utils.encodeCompactBits(maxTarget);
                } else {
                    // Return the last non-special-min-difficulty-rules-block
                    StoredBlock cursor = storedPrev;

                    while (cursor.getHeight() % retargetInterval != 0
                            && cursor.getHeader().getDifficultyTarget() == Utils.encodeCompactBits(this.getMaxTarget())) {
                        StoredBlock prevCursor = cursor.getPrev(blockStore);
                        if (prevCursor == null) {
                            break;
                        }
                        cursor = prevCursor;
                    }

                    return cursor.getHeader().getDifficultyTarget();
                }
            }

            // No ... so check the difficulty didn't actually change.
            return prev.getDifficultyTarget();
        }

        // We need to find a block far back in the chain. It's OK that this is expensive because it only occurs every
        // two weeks after the initial block chain download.
        StoredBlock cursor = storedPrev;
        int goBack = retargetInterval - 1;

        // Litecoin: This fixes an issue where a 51% attack can change difficulty at will.
        // Go back the full period unless it's the first retarget after genesis.
        // Code based on original by Art Forz
        if (cursor.getHeight()+1 != retargetInterval)
            goBack = retargetInterval;

        for (int i = 0; i < goBack; i++) {
            if (cursor == null) {
                // This should never happen. If it does, it means we are following an incorrect or busted chain.
                throw new VerificationException(
                        "Difficulty transition point but we did not find a way back to the genesis block.");
            }
            cursor = blockStore.get(cursor.getHeader().getPrevBlockHash());
        }

        //We used checkpoints...
        if (cursor == null) {
            log.debug("Difficulty transition: Hit checkpoint!");
            throw new CheckpointEncounteredException();
        }

        Block blockIntervalAgo = cursor.getHeader();
        return this.calculateNewDifficultyTargetInner(previousHeight, prev.getTimeSeconds(),
            prev.getDifficultyTarget(), blockIntervalAgo.getTimeSeconds(),
            nextBlock.getDifficultyTarget());
    }

    /**
     * Calculate the difficulty target expected for the next block after a normal
     * recalculation interval. Does not handle special cases such as testnet blocks
     * being setting the target to maximum for blocks after a long interval.
     *
     * @param previousHeight height of the block immediately before the retarget.
     * @param prev the block immediately before the retarget block.
     * @param nextBlock the block the retarget happens at.
     * @param blockIntervalAgo The last retarget block.
     * @return New difficulty target as compact bytes.
     */
    protected long calculateNewDifficultyTargetInner(int previousHeight, final Block prev,
            final Block nextBlock, final Block blockIntervalAgo) {
        return this.calculateNewDifficultyTargetInner(previousHeight, prev.getTimeSeconds(),
            prev.getDifficultyTarget(), blockIntervalAgo.getTimeSeconds(),
            nextBlock.getDifficultyTarget());
    }

    /**
     *
     * @param previousHeight Height of the block immediately previous to the one we're calculating difficulty of.
     * @param previousBlockTime Time of the block immediately previous to the one we're calculating difficulty of.
     * @param lastDifficultyTarget Compact difficulty target of the last retarget block.
     * @param lastRetargetTime Time of the last difficulty retarget.
     * @param nextDifficultyTarget The expected difficulty target of the next
     * block, used for determining precision of the result.
     * @return New difficulty target as compact bytes.
     */
    protected long calculateNewDifficultyTargetInner(int previousHeight, long previousBlockTime,
        final long lastDifficultyTarget, final long lastRetargetTime,
        final long nextDifficultyTarget) {
        final int retargetTimespan = this.getTargetTimespan();
        int actualTime = (int) (previousBlockTime - lastRetargetTime);
        final int minTimespan = retargetTimespan / 4;
        final int maxTimespan = retargetTimespan * 4;

        actualTime = Math.min(maxTimespan, Math.max(minTimespan, actualTime));

        BigInteger newTarget = Utils.decodeCompactBits(lastDifficultyTarget);
        newTarget = newTarget.multiply(BigInteger.valueOf(actualTime));
        newTarget = newTarget.divide(BigInteger.valueOf(retargetTimespan));

        if (newTarget.compareTo(this.getMaxTarget()) > 0) {
            log.info("Difficulty hit proof of work limit: {}", newTarget.toString(16));
            newTarget = this.getMaxTarget();
        }

        int accuracyBytes = (int) (nextDifficultyTarget >>> 24) - 3;

        // The calculated difficulty is to a higher precision than received, so reduce here.
        BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes * 8);
        newTarget = newTarget.and(mask);
        return Utils.encodeCompactBits(newTarget);
    }

    @Override
    public AltcoinSerializer getSerializer(boolean parseRetain) {
        return new AltcoinSerializer(this, parseRetain);
    }

    @Override
    public int getProtocolVersionNum(final ProtocolVersion version) {
        switch (version) {
            case PONG:
            case BLOOM_FILTER:
                return version.getBitcoinProtocolVersion();
            case CURRENT:
                return protocolVersionCurrent;
            case MINIMUM:
            default:
                return protocolVersionMinimum;
        }
    }

    /**
     * Whether this network has special rules to enable minimum difficulty blocks
     * after a long interval between two blocks (i.e. testnet).
     */
    public boolean allowMinDifficultyBlocks() {
        return this.isTestNet();
    }

    public int getTargetSpacing() {
        return this.getTargetTimespan() / this.getInterval();
    }

    private static class CheckpointEncounteredException extends Exception {  }
}
