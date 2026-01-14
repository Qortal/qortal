package org.qortal.asset;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.account.Account;
import org.qortal.account.PublicKeyAccount;
import org.qortal.data.asset.AssetData;
import org.qortal.data.asset.OrderData;
import org.qortal.data.asset.TradeData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Amounts;
import org.qortal.utils.Base58;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.qortal.utils.Amounts.prettyAmount;

public class Order {

	private static final Logger LOGGER = LogManager.getLogger(Order.class);

	// Properties
	private Repository repository;
	private OrderData orderData;

	// Used quite a bit
	private final long haveAssetId;
	private final long wantAssetId;
	private final boolean isAmountInWantAsset;
	private final BigInteger orderAmount;
	private final BigInteger orderPrice;

	/** Cache of price-pair units e.g. QORT/GOLD, but use getPricePair() instead! */
	private String cachedPricePair;

	/** Cache of have-asset data - but use getHaveAsset() instead! */
	AssetData cachedHaveAssetData;
	/** Cache of want-asset data - but use getWantAsset() instead! */
	AssetData cachedWantAssetData;

	// Constructors

	public Order(Repository repository, OrderData orderData) {
		this.repository = repository;
		this.orderData = orderData;

		this.haveAssetId = this.orderData.getHaveAssetId();
		this.wantAssetId = this.orderData.getWantAssetId();
		this.isAmountInWantAsset = haveAssetId < wantAssetId;

		this.orderAmount = BigInteger.valueOf(this.orderData.getAmount());
		this.orderPrice = BigInteger.valueOf(this.orderData.getPrice());
	}

	// Getters/Setters

	public OrderData getOrderData() {
		return this.orderData;
	}

	// More information

	public static long getAmountLeft(OrderData orderData) {
		return orderData.getAmount() - orderData.getFulfilled();
	}

	public long getAmountLeft() {
		return Order.getAmountLeft(this.orderData);
	}

	public static boolean isFulfilled(OrderData orderData) {
		return orderData.getFulfilled() == orderData.getAmount();
	}

	public boolean isFulfilled() {
		return Order.isFulfilled(this.orderData);
	}

	/**
	 * Returns granularity/batch-size of matched-amount, given price, so that return-amount is valid size.
	 * <p>
	 * If matched-amount of matched-asset is traded when two orders match,
	 * then the corresponding return-amount of the other (return) asset needs to be either
	 * an integer, if return-asset is indivisible,
	 * or to the nearest 0.00000001 if return-asset is divisible.
	 * <p>
	 * @return granularity of matched-amount
	 */
	public static long calculateAmountGranularity(boolean isAmountAssetDivisible, boolean isReturnAssetDivisible, long price) {
		// Calculate the minimum increment for matched-amount using greatest-common-divisor
		BigInteger returnAmount = Amounts.MULTIPLIER_BI; // 1 unit * multiplier
		BigInteger matchedAmount = BigInteger.valueOf(price);

		BigInteger gcd = returnAmount.gcd(matchedAmount);
		returnAmount = returnAmount.divide(gcd);
		matchedAmount = matchedAmount.divide(gcd);

		// Calculate GCD in combination with divisibility
		if (isAmountAssetDivisible)
			returnAmount = returnAmount.multiply(Amounts.MULTIPLIER_BI);

		if (isReturnAssetDivisible)
			matchedAmount = matchedAmount.multiply(Amounts.MULTIPLIER_BI);

		gcd = returnAmount.gcd(matchedAmount);

		// Calculate the granularity at which we have to buy
		BigInteger granularity = returnAmount.multiply(Amounts.MULTIPLIER_BI).divide(gcd);
		if (isAmountAssetDivisible)
			granularity = granularity.divide(Amounts.MULTIPLIER_BI);

		// Return
		return granularity.longValue();
	}

	/**
	 * Returns price-pair in string form.
	 * <p>
	 * e.g. <tt>"QORT/GOLD"</tt>
	 */
	public String getPricePair() throws DataException {
		if (cachedPricePair == null)
			calcPricePair();

		return cachedPricePair;
	}

	/** Calculate price pair. (e.g. QORT/GOLD)
	 * <p>
	 * Lowest-assetID asset is first,
	 * so if QORT has assetID 0 and GOLD has assetID 10, then
	 * the pricing pair is QORT/GOLD.
	 * <p>
	 * This means the "amount" fields are expressed in terms
	 * of the higher-assetID asset. (e.g. GOLD)
	 */
	private void calcPricePair() throws DataException {
		AssetData haveAssetData = getHaveAsset();
		AssetData wantAssetData = getWantAsset();

		if (haveAssetId > wantAssetId)
			cachedPricePair = wantAssetData.getName() + "/" + haveAssetData.getName();
		else
			cachedPricePair = haveAssetData.getName() + "/" + wantAssetData.getName();
	}

	/** Returns amount of have-asset to remove from order's creator's balance on placing this order. */
	private long calcHaveAssetCommittment() {
		// Simple case: amount is in have asset
		if (!this.isAmountInWantAsset)
			return this.orderData.getAmount();

		return Amounts.roundUpScaledMultiply(this.orderAmount, this.orderPrice);
	}

	private long calcHaveAssetRefund(long amount) {
		// Simple case: amount is in have asset
		if (!this.isAmountInWantAsset)
			return amount;

		return Amounts.roundUpScaledMultiply(BigInteger.valueOf(amount), this.orderPrice);
	}

	/** Returns amount of remaining have-asset to refund to order's creator's balance on cancelling this order. */
	private long calcHaveAssetRefund() {
		return calcHaveAssetRefund(getAmountLeft());
	}

	// Navigation

	public List<TradeData> getTrades() throws DataException {
		return this.repository.getAssetRepository().getOrdersTrades(this.orderData.getOrderId());
	}

	public AssetData getHaveAsset() throws DataException {
		if (cachedHaveAssetData == null)
			cachedHaveAssetData = this.repository.getAssetRepository().fromAssetId(haveAssetId);

		return cachedHaveAssetData;
	}

	public AssetData getWantAsset() throws DataException {
		if (cachedWantAssetData == null)
			cachedWantAssetData = this.repository.getAssetRepository().fromAssetId(wantAssetId);

		return cachedWantAssetData;
	}

	/**
	 * Returns AssetData for asset in effect for "amount" field.
	 * <p>
	 * This is the asset with highest assetID.
	 */
	public AssetData getAmountAsset() throws DataException {
		return (wantAssetId > haveAssetId) ? getWantAsset() : getHaveAsset();
	}

	/**
	 * Returns AssetData for other (return) asset traded.
	 * <p>
	 * This is the asset with lowest assetID.
	 */
	public AssetData getReturnAsset() throws DataException {
		return (haveAssetId < wantAssetId) ? getHaveAsset() : getWantAsset();
	}

	// Processing

	private void logOrder(String orderPrefix, boolean isOurOrder, OrderData orderData) throws DataException {
		// Avoid calculations if possible
		if (LOGGER.getLevel().isMoreSpecificThan(Level.DEBUG))
			return;

		final String weThey = isOurOrder ? "We" : "They";
		final String ourTheir = isOurOrder ? "Our" : "Their";

		// NOTE: the following values are specific to passed orderData, not the same as class instance values!

		// Cached for readability
		final long _haveAssetId = orderData.getHaveAssetId();
		final long _wantAssetId = orderData.getWantAssetId();

		final AssetData haveAssetData = this.repository.getAssetRepository().fromAssetId(_haveAssetId);
		final AssetData wantAssetData = this.repository.getAssetRepository().fromAssetId(_wantAssetId);

		final long amountAssetId = (_wantAssetId > _haveAssetId) ? _wantAssetId : _haveAssetId;
		final long returnAssetId = (_haveAssetId < _wantAssetId) ? _haveAssetId : _wantAssetId;

		final AssetData amountAssetData = this.repository.getAssetRepository().fromAssetId(amountAssetId);
		final AssetData returnAssetData = this.repository.getAssetRepository().fromAssetId(returnAssetId);

		LOGGER.debug(() -> String.format("%s %s", orderPrefix, Base58.encode(orderData.getOrderId())));

		LOGGER.trace(() -> String.format("%s have %s, want %s.", weThey, haveAssetData.getName(), wantAssetData.getName()));

		LOGGER.trace(() -> String.format("%s amount: %s (ordered) - %s (fulfilled) = %s %s left", ourTheir,
				prettyAmount(orderData.getAmount()),
				prettyAmount(orderData.getFulfilled()),
				prettyAmount(Order.getAmountLeft(orderData)),
				amountAssetData.getName()));

		long maxReturnAmount = Amounts.roundUpScaledMultiply(Order.getAmountLeft(orderData), orderData.getPrice());
		String pricePair = getPricePair();

		LOGGER.trace(() -> String.format("%s price: %s %s (%s %s tradable)", ourTheir,
				prettyAmount(orderData.getPrice()),
				pricePair,
				prettyAmount(maxReturnAmount),
				returnAssetData.getName()));
	}

	public void process() throws DataException {
		// Subtract have-asset from creator
		Account creator = new PublicKeyAccount(this.repository, this.orderData.getCreatorPublicKey());
		creator.modifyAssetBalance(haveAssetId, - this.calcHaveAssetCommittment());

		// Save this order into repository so it's available for matching, possibly by itself
		this.repository.getAssetRepository().save(this.orderData);

		logOrder("Processing our order", true, this.orderData);

		// Fetch corresponding open orders that might potentially match, hence reversed want/have assetIDs.
		// Returned orders are sorted with lowest "price" first.
		List<OrderData> orders = this.repository.getAssetRepository().getOpenOrdersForTrading(wantAssetId, haveAssetId, this.orderData.getPrice());
		LOGGER.trace(() -> String.format("Open orders fetched from repository: %d", orders.size()));

		if (orders.isEmpty())
			return;

		matchOrders(orders);
	}

	private void matchOrders(List<OrderData> orders) throws DataException {
		AssetData haveAssetData = getHaveAsset();
		AssetData wantAssetData = getWantAsset();

		/** The asset while working out amount that matches. */
		AssetData matchingAssetData = getAmountAsset();
		/** The return asset traded if trade completes. */
		AssetData returnAssetData = getReturnAsset();

		// Attempt to match orders

		/*
		 * Potential matching order example:
		 * 
		 * Our order:
		 * haveAssetId=[GOLD], wantAssetId=0 (QORT), amount=40 (GOLD), price=486 (QORT/GOLD)
		 * This translates to "we have 40 GOLD and want QORT at a price of 486 QORT per GOLD"
		 * If our order matched, we'd end up with 19,440 QORT at a cost of 19,440 / 486 = 40 GOLD.
		 * 
		 * Their order:
		 * haveAssetId=0 (QORT), wantAssetId=[GOLD], amount=40 (GOLD), price=486.00074844 (QORT/GOLD)
		 * This translates to "they have QORT and want GOLD at a price of 486.00074844 QORT per GOLD"
		 * 
		 * Their price is better than our requested 486 QORT/GOLD so this order matches.
		 * 
		 * Using their price, we end up with 40 * 486.00074844 = 19440.02993760 QORT. They end up with 40 GOLD.
		 * 
		 * If their order only had 36 GOLD left, only 36 * 486.00074844 = 17496.02694384 QORT would be traded.
		 */

		long ourPrice = this.orderData.getPrice();
		String pricePair = getPricePair();

		for (OrderData theirOrderData : orders) {
			logOrder("Considering order", false, theirOrderData);

			// Determine their order price
			long theirPrice = theirOrderData.getPrice();
			LOGGER.trace(() -> String.format("Their price: %s %s", prettyAmount(theirPrice), pricePair));

			// If their price is worse than what we're willing to accept then we're done as prices only get worse as we iterate through list of orders
			if ((haveAssetId < wantAssetId && theirPrice > ourPrice) || (haveAssetId > wantAssetId && theirPrice < ourPrice))
				break;

			// Calculate how much we could buy at their price, "amount" is expressed in terms of asset with highest assetID.
			long ourMaxAmount = this.getAmountLeft();
			LOGGER.trace(() -> String.format("ourMaxAmount (max we could trade at their price): %s %s", prettyAmount(ourMaxAmount), matchingAssetData.getName()));

			// How much is remaining available in their order.
			long theirAmountLeft = Order.getAmountLeft(theirOrderData);
			LOGGER.trace(() -> String.format("theirAmountLeft (max amount remaining in their order): %s %s", prettyAmount(theirAmountLeft), matchingAssetData.getName()));

			// So matchable want-asset amount is the minimum of above two values
			long interimMatchedAmount = Math.min(ourMaxAmount, theirAmountLeft);
			LOGGER.trace(() -> String.format("matchedAmount: %s %s", prettyAmount(interimMatchedAmount), matchingAssetData.getName()));

			// If we can't buy anything then try another order
			if (interimMatchedAmount <= 0)
				continue;

			// Calculate amount granularity, based on price and both assets' divisibility, so that return-amount traded is a valid value (integer or to 8 d.p.)
			long granularity = calculateAmountGranularity(matchingAssetData.isDivisible(), returnAssetData.isDivisible(), theirOrderData.getPrice());
			LOGGER.trace(() -> String.format("granularity (amount granularity): %s %s", prettyAmount(granularity), matchingAssetData.getName()));

			// Reduce matched amount (if need be) to fit granularity
			long matchedAmount = interimMatchedAmount - interimMatchedAmount % granularity;
			LOGGER.trace(() -> String.format("matchedAmount adjusted for granularity: %s %s", prettyAmount(matchedAmount), matchingAssetData.getName()));

			// If we can't buy anything then try another order
			if (matchedAmount <= 0)
				continue;

			// Safety check
			checkDivisibility(matchingAssetData, matchedAmount, theirOrderData);

			// Trade can go ahead!

			// Calculate the total cost to us, in return-asset, based on their price
			long returnAmountTraded = Amounts.roundDownScaledMultiply(matchedAmount, theirOrderData.getPrice());
			LOGGER.trace(() -> String.format("returnAmountTraded: %s %s", prettyAmount(returnAmountTraded), returnAssetData.getName()));

			// Safety check
			checkDivisibility(returnAssetData, returnAmountTraded, this.orderData);

			long tradedWantAmount = this.isAmountInWantAsset ? matchedAmount : returnAmountTraded;
			long tradedHaveAmount = this.isAmountInWantAsset ? returnAmountTraded : matchedAmount;

			// We also need to know how much have-asset to refund based on price improvement (only one direction applies)
			long haveAssetRefund = this.isAmountInWantAsset ? Amounts.roundDownScaledMultiply(matchedAmount, Math.abs(ourPrice - theirPrice)) : 0;

			LOGGER.trace(() -> String.format("We traded %s %s (have-asset) for %s %s (want-asset), saving %s %s (have-asset)",
					prettyAmount(tradedHaveAmount), haveAssetData.getName(),
					prettyAmount(tradedWantAmount), wantAssetData.getName(),
					prettyAmount(haveAssetRefund), haveAssetData.getName()));

			// Construct trade
			TradeData tradeData = new TradeData(this.orderData.getOrderId(), theirOrderData.getOrderId(),
					tradedWantAmount, tradedHaveAmount, haveAssetRefund, this.orderData.getTimestamp());

			// Process trade, updating corresponding orders in repository
			Trade trade = new Trade(this.repository, tradeData);
			trade.process();

			// Update our order in terms of fulfillment, etc. but do not save into repository as that's handled by Trade above
			long amountFulfilled = matchedAmount;
			this.orderData.setFulfilled(this.orderData.getFulfilled() + amountFulfilled);
			LOGGER.trace(() -> String.format("Updated our order's fulfilled amount to: %s %s", prettyAmount(this.orderData.getFulfilled()), matchingAssetData.getName()));
			LOGGER.trace(() -> String.format("Our order's amount remaining: %s %s", prettyAmount(this.getAmountLeft()), matchingAssetData.getName()));

			// Continue on to process other open orders if we still have amount left to match
			if (this.getAmountLeft() <= 0)
				break;
		}
	}

	/**
	 * Check amount has no fractional part if asset is indivisible.
	 * 
	 * @throws DataException if divisibility check fails
	 */
	private void checkDivisibility(AssetData assetData, long amount, OrderData orderData) throws DataException {
		if (assetData.isDivisible() || amount % Amounts.MULTIPLIER == 0)
			// Asset is divisible or amount has no fractional part
			return;

		String message = String.format("Refusing to trade fractional %s [indivisible assetID %d] for order %s",
				prettyAmount(amount), assetData.getAssetId(), Base58.encode(orderData.getOrderId()));
		LOGGER.error(message);
		throw new DataException(message);
	}

	public void orphan() throws DataException {
		// Orphan trades that occurred as a result of this order
		for (TradeData tradeData : getTrades())
			if (Arrays.equals(this.orderData.getOrderId(), tradeData.getInitiator())) {
				Trade trade = new Trade(this.repository, tradeData);
				trade.orphan();
			}

		// Delete this order from repository
		this.repository.getAssetRepository().delete(this.orderData.getOrderId());

		// Return asset to creator
		Account creator = new PublicKeyAccount(this.repository, this.orderData.getCreatorPublicKey());
		creator.modifyAssetBalance(haveAssetId, this.calcHaveAssetCommittment());
	}

	// This is called by CancelOrderTransaction so that an Order can no longer trade
	public void cancel() throws DataException {
		this.orderData.setIsClosed(true);
		this.repository.getAssetRepository().save(this.orderData);

		// Update creator's balance with unfulfilled amount
		Account creator = new PublicKeyAccount(this.repository, this.orderData.getCreatorPublicKey());
		creator.modifyAssetBalance(haveAssetId, calcHaveAssetRefund());
	}

	// Opposite of cancel() above for use during orphaning
	public void reopen() throws DataException {
		// Update creator's balance with unfulfilled amount
		Account creator = new PublicKeyAccount(this.repository, this.orderData.getCreatorPublicKey());
		creator.modifyAssetBalance(haveAssetId, - calcHaveAssetRefund());

		this.orderData.setIsClosed(false);
		this.repository.getAssetRepository().save(this.orderData);
	}

}
