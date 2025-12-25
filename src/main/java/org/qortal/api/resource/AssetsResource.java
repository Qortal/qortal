package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiException;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.model.AggregatedOrder;
import org.qortal.api.model.TradeWithOrderInfo;
import org.qortal.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortal.asset.Asset;
import org.qortal.controller.hsqldb.HSQLDBBalanceRecorder;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AddressAmountData;
import org.qortal.data.account.BlockHeightRange;
import org.qortal.data.account.BlockHeightRangeAddressAmounts;
import org.qortal.data.asset.AssetData;
import org.qortal.data.asset.OrderData;
import org.qortal.data.asset.RecentTradeData;
import org.qortal.data.asset.TradeData;
import org.qortal.data.transaction.*;
import org.qortal.repository.AccountRepository.BalanceOrdering;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.*;
import org.qortal.utils.BalanceRecorderUtils;
import org.qortal.utils.Base58;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Path("/assets")
@Tag(name = "Assets")
public class AssetsResource {

	@Context
	HttpServletRequest request;

	@GET
	@Operation(
		summary = "List all known assets (without data field)",
		responses = {
			@ApiResponse(
				description = "asset info",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = AssetData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.REPOSITORY_ISSUE
	})
	public List<AssetData> getAllAssets(@QueryParam("includeData") Boolean includeData,
	@Parameter(
		ref = "limit"
	) @QueryParam("limit") Integer limit, @Parameter(
		ref = "offset"
	) @QueryParam("offset") Integer offset, @Parameter(
		ref = "reverse"
	) @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<AssetData> assets = repository.getAssetRepository().getAllAssets(limit, offset, reverse);

			if (includeData == null || !includeData)
				assets.forEach(asset -> asset.setData(null));

			return assets;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/info")
	@Operation(
		summary = "Info on specific asset",
		description = "Supply either assetId OR assetName. (If both supplied, assetId takes priority).",
		responses = {
			@ApiResponse(
				description = "asset info",
				content = @Content(
					schema = @Schema(
						implementation = AssetData.class
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_CRITERIA, ApiError.INVALID_ASSET_ID, ApiError.REPOSITORY_ISSUE
	})
	public AssetData getAssetInfo(@QueryParam("assetId") Integer assetId, @QueryParam("assetName") String assetName) {
		if (assetId == null && (assetName == null || assetName.isEmpty()))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		try (final Repository repository = RepositoryManager.getRepository()) {
			AssetData assetData = null;

			if (assetId != null)
				assetData = repository.getAssetRepository().fromAssetId(assetId);
			else
				assetData = repository.getAssetRepository().fromAssetName(assetName);

			if (assetData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			return assetData;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/balances")
	@Operation(
		summary = "Asset balances owned by addresses and/or filtered to subset of assetIDs",
		description = "Returns asset balances for these addresses/assetIDs, with balances. At least one address or assetID must be supplied.",
		responses = {
			@ApiResponse(
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = AccountBalanceData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_ADDRESS, ApiError.INVALID_CRITERIA, ApiError.INVALID_ASSET_ID, ApiError.REPOSITORY_ISSUE
	})
	public List<AccountBalanceData> getAssetBalances(@QueryParam("address") List<String> addresses, @QueryParam("assetid") List<Long> assetIds,
			@DefaultValue(value = "ASSET_BALANCE_ACCOUNT") @QueryParam("ordering") BalanceOrdering balanceOrdering,
			@QueryParam("excludeZero") Boolean excludeZero,
			@Parameter( ref = "limit" ) @QueryParam("limit") Integer limit,
			@Parameter( ref = "offset" ) @QueryParam("offset") Integer offset,
			@Parameter( ref = "reverse" ) @QueryParam("reverse") Boolean reverse) {
		if (addresses.isEmpty() && assetIds.isEmpty())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		for (String address : addresses)
			if (!Crypto.isValidAddress(address))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		if (balanceOrdering == null)
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);

		try (final Repository repository = RepositoryManager.getRepository()) {
			for (long assetId : assetIds)
				if (!repository.getAssetRepository().assetExists(assetId))
					throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			return repository.getAccountRepository().getAssetBalances(addresses, assetIds, balanceOrdering, excludeZero, limit, offset, reverse);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/balancedynamicranges")
	@Operation(
			summary = "Get balance dynamic ranges listed.",
			description = ".",
			responses = {
					@ApiResponse(
							content = @Content(
									array = @ArraySchema(
											schema = @Schema(
													implementation = BlockHeightRange.class
											)
									)
							)
					)
			}
	)
	public List<BlockHeightRange> getBalanceDynamicRanges(
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit,
			@Parameter(ref = "reverse") @QueryParam("reverse") Boolean reverse) {

		Optional<HSQLDBBalanceRecorder> recorder = Optional.ofNullable(HSQLDBBalanceRecorder.getInstance());

		if( recorder.isPresent()) {
			return recorder.get().getRanges(offset, limit, reverse);
		}
		else {
			return new ArrayList<>(0);
		}
	}

	@GET
	@Path("/balancedynamicrange/{height}")
	@Operation(
			summary = "Get balance dynamic range for a given height.",
			description = ".",
			responses = {
					@ApiResponse(
							content = @Content(
										schema = @Schema(
												implementation = BlockHeightRange.class
										)
							)
					)
			}
	)
	@ApiErrors({
			ApiError.INVALID_CRITERIA, ApiError.INVALID_DATA
	})
	public BlockHeightRange getBalanceDynamicRange(@PathParam("height") int height) {

		Optional<HSQLDBBalanceRecorder> recorder = Optional.ofNullable(HSQLDBBalanceRecorder.getInstance());

		if( recorder.isPresent()) {
			Optional<BlockHeightRange> range = recorder.get().getRange(height);

			if( range.isPresent() ) {
				return range.get();
			}
			else {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
			}
		}
		else {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		}
	}

	@GET
	@Path("/balancedynamicamounts/{begin}/{end}")
	@Operation(
			summary = "Get balance dynamic ranges address amounts listed.",
			description = ".",
			responses = {
					@ApiResponse(
							content = @Content(
									array = @ArraySchema(
											schema = @Schema(
													implementation = AddressAmountData.class
											)
									)
							)
					)
			}
	)
	@ApiErrors({
			ApiError.INVALID_CRITERIA, ApiError.INVALID_DATA
	})
	public List<AddressAmountData> getBalanceDynamicAddressAmounts(
			@PathParam("begin") int begin,
			@PathParam("end") int end,
			@Parameter(ref = "offset") @QueryParam("offset") Integer offset,
			@Parameter(ref = "limit") @QueryParam("limit") Integer limit) {

		Optional<HSQLDBBalanceRecorder> recorder = Optional.ofNullable(HSQLDBBalanceRecorder.getInstance());

		if( recorder.isPresent()) {
			Optional<BlockHeightRangeAddressAmounts> addressAmounts = recorder.get().getAddressAmounts(new BlockHeightRange(begin, end, false));

			if( addressAmounts.isPresent() ) {
				return addressAmounts.get().getAmounts().stream()
					.sorted(BalanceRecorderUtils.ADDRESS_AMOUNT_DATA_COMPARATOR.reversed())
					.skip(offset)
					.limit(limit)
					.collect(Collectors.toList());
			}
			else {
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
			}
		}
		else {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_DATA);
		}
	}

	@GET
	@Path("/openorders/{assetid}/{otherassetid}")
	@Operation(
		summary = "Detailed asset open order book",
		description = "Returns open orders, offering {assetid} for {otherassetid} in return.",
		responses = {
			@ApiResponse(
				description = "asset orders",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = OrderData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_ASSET_ID, ApiError.REPOSITORY_ISSUE
	})
	public List<OrderData> getOpenOrders(@Parameter(
		ref = "assetid"
	) @PathParam("assetid") int assetId, @Parameter(
		ref = "otherassetid"
	) @PathParam("otherassetid") int otherAssetId, @Parameter(
		ref = "limit"
	) @QueryParam("limit") Integer limit, @Parameter(
		ref = "offset"
	) @QueryParam("offset") Integer offset, @Parameter(
		ref = "reverse"
	) @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			if (!repository.getAssetRepository().assetExists(assetId))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			if (!repository.getAssetRepository().assetExists(otherAssetId))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			return repository.getAssetRepository().getOpenOrders(assetId, otherAssetId, limit, offset, reverse);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/orderbook/{assetid}/{otherassetid}")
	@Operation(
		summary = "Aggregated asset open order book",
		description = "Returns open orders, offering {assetid} for {otherassetid} in return.",
		responses = {
			@ApiResponse(
				description = "asset orders",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = AggregatedOrder.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_ASSET_ID, ApiError.REPOSITORY_ISSUE
	})
	public List<AggregatedOrder> getAggregatedOpenOrders(@Parameter(
		ref = "assetid"
	) @PathParam("assetid") int assetId, @Parameter(
		ref = "otherassetid"
	) @PathParam("otherassetid") int otherAssetId, @Parameter(
		ref = "limit"
	) @QueryParam("limit") Integer limit, @Parameter(
		ref = "offset"
	) @QueryParam("offset") Integer offset, @Parameter(
		ref = "reverse"
	) @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			if (!repository.getAssetRepository().assetExists(assetId))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			if (!repository.getAssetRepository().assetExists(otherAssetId))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			List<OrderData> orders = repository.getAssetRepository().getAggregatedOpenOrders(assetId, otherAssetId, limit, offset, reverse);

			// Map to aggregated form
			return orders.stream().map(AggregatedOrder::new).collect(Collectors.toList());
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/trades/recent")
	@Operation(
		summary = "Most recent asset trades",
		description = "Returns list of most recent two asset trades for each assetID passed. Other assetID optional.",
		responses = {
			@ApiResponse(
				description = "asset trades",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = RecentTradeData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_ASSET_ID, ApiError.REPOSITORY_ISSUE
	})
	public List<RecentTradeData> getRecentTrades(@QueryParam("assetid") List<Long> assetIds, @QueryParam("otherassetid") List<Long> otherAssetIds, @Parameter(
		ref = "limit"
	) @QueryParam("limit") Integer limit, @Parameter(
		ref = "offset"
	) @QueryParam("offset") Integer offset, @Parameter(
		ref = "reverse"
	) @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			if (assetIds.isEmpty())
				assetIds = Collections.singletonList(Asset.QORT);
			else
				for (long assetId : assetIds)
					if (!repository.getAssetRepository().assetExists(assetId))
						throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			if (otherAssetIds.isEmpty())
				otherAssetIds = Collections.singletonList(Asset.QORT);
			else
				for (long assetId : otherAssetIds)
					if (!repository.getAssetRepository().assetExists(assetId))
						throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			return repository.getAssetRepository().getRecentTrades(assetIds, otherAssetIds, limit, offset, reverse);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/trades/{assetid}/{otherassetid}")
	@Operation(
		summary = "Asset trades",
		description = "Returns successful trades of {assetid} for {otherassetid}.<br>" + "Does NOT include trades of {otherassetid} for {assetid}!<br>"
				+ "\"Initiating\" order is the order that caused the actual trade by matching up with the \"target\" order.",
		responses = {
			@ApiResponse(
				description = "asset trades",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = TradeWithOrderInfo.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_ASSET_ID, ApiError.REPOSITORY_ISSUE
	})
	public List<TradeWithOrderInfo> getAssetTrades(@Parameter(
		ref = "assetid"
	) @PathParam("assetid") int assetId, @Parameter(
		ref = "otherassetid"
	) @PathParam("otherassetid") int otherAssetId, @Parameter(
		ref = "limit"
	) @QueryParam("limit") Integer limit, @Parameter(
		ref = "offset"
	) @QueryParam("offset") Integer offset, @Parameter(
		ref = "reverse"
	) @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			if (!repository.getAssetRepository().assetExists(assetId))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			if (!repository.getAssetRepository().assetExists(otherAssetId))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			List<TradeData> trades = repository.getAssetRepository().getTrades(assetId, otherAssetId, limit, offset, reverse);

			// Expanding remaining entries
			List<TradeWithOrderInfo> fullTrades = new ArrayList<>();
			for (TradeData tradeData : trades) {
				OrderData initiatingOrderData = repository.getAssetRepository().fromOrderId(tradeData.getInitiator());
				OrderData targetOrderData = repository.getAssetRepository().fromOrderId(tradeData.getTarget());
				fullTrades.add(new TradeWithOrderInfo(tradeData, initiatingOrderData, targetOrderData));
			}

			return fullTrades;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/order/{orderid}")
	@Operation(
		summary = "Fetch asset order",
		description = "Returns asset order info.",
		responses = {
			@ApiResponse(
				description = "asset order",
				content = @Content(
					schema = @Schema(
						implementation = OrderData.class
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_ORDER_ID, ApiError.ORDER_UNKNOWN, ApiError.REPOSITORY_ISSUE
	})
	public OrderData getAssetOrder(@PathParam("orderid") String orderId58) {
		// Decode orderID
		byte[] orderId;
		try {
			orderId = Base58.decode(orderId58);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ORDER_ID, e);
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			OrderData orderData = repository.getAssetRepository().fromOrderId(orderId);
			if (orderData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ORDER_UNKNOWN);

			return orderData;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/order/{orderid}/trades")
	@Operation(
		summary = "Fetch asset order's matching trades",
		description = "Returns asset order trades",
		responses = {
			@ApiResponse(
				description = "asset trades",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = TradeData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_ORDER_ID, ApiError.ORDER_UNKNOWN, ApiError.REPOSITORY_ISSUE
	})
	public List<TradeData> getAssetOrderTrades(@PathParam("orderid") String orderId58, @Parameter(
		ref = "limit"
	) @QueryParam("limit") Integer limit, @Parameter(
		ref = "offset"
	) @QueryParam("offset") Integer offset, @Parameter(
		ref = "reverse"
	) @QueryParam("reverse") Boolean reverse) {
		// Decode orderID
		byte[] orderId;
		try {
			orderId = Base58.decode(orderId58);
		} catch (NumberFormatException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ORDER_ID, e);
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			OrderData orderData = repository.getAssetRepository().fromOrderId(orderId);
			if (orderData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ORDER_UNKNOWN);

			return repository.getAssetRepository().getOrdersTrades(orderId, limit, offset, reverse);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/orders/{address}")
	@Operation(
		summary = "Asset orders created by this address",
		responses = {
			@ApiResponse(
				description = "Asset orders",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = OrderData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN, ApiError.REPOSITORY_ISSUE
	})
	public List<OrderData> getAccountOrders(@PathParam("address") String address, @QueryParam("includeClosed") boolean includeClosed,
			@QueryParam("includeFulfilled") boolean includeFulfilled, @Parameter(
				ref = "limit"
			) @QueryParam("limit") Integer limit, @Parameter(
				ref = "offset"
			) @QueryParam("offset") Integer offset, @Parameter(
				ref = "reverse"
			) @QueryParam("reverse") Boolean reverse) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			AccountData accountData = repository.getAccountRepository().getAccount(address);

			if (accountData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			byte[] publicKey = accountData.getPublicKey();
			if (publicKey == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			return repository.getAssetRepository().getAccountsOrders(publicKey, includeClosed, includeFulfilled, limit, offset, reverse);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/orders/{address}/{assetid}/{otherassetid}")
	@Operation(
		summary = "Asset orders created by this address, limited to one specific asset pair",
		responses = {
			@ApiResponse(
				description = "Asset orders",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = OrderData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_ADDRESS, ApiError.ADDRESS_UNKNOWN, ApiError.REPOSITORY_ISSUE
	})
	public List<OrderData> getAccountAssetPairOrders(@PathParam("address") String address, @PathParam("assetid") int assetId,
			@PathParam("otherassetid") int otherAssetId, @QueryParam("isClosed") Boolean isClosed, @QueryParam("isFulfilled") Boolean isFulfilled, @Parameter(
				ref = "limit"
			) @QueryParam("limit") Integer limit, @Parameter(
				ref = "offset"
			) @QueryParam("offset") Integer offset, @Parameter(
				ref = "reverse"
			) @QueryParam("reverse") Boolean reverse) {
		if (!Crypto.isValidAddress(address))
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			AccountData accountData = repository.getAccountRepository().getAccount(address);

			if (accountData == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			byte[] publicKey = accountData.getPublicKey();
			if (publicKey == null)
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.ADDRESS_UNKNOWN);

			if (!repository.getAssetRepository().assetExists(assetId))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			if (!repository.getAssetRepository().assetExists(otherAssetId))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			return repository.getAssetRepository().getAccountsOrders(publicKey, assetId, otherAssetId, isClosed, isFulfilled, limit, offset, reverse);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/transactions/{assetid}")
	@Operation(
		summary = "Transactions related to asset",
		responses = {
			@ApiResponse(
				description = "Asset transactions",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = TransactionData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_ADDRESS, ApiError.INVALID_ASSET_ID, ApiError.REPOSITORY_ISSUE
	})
	public List<TransactionData> getAssetTransactions(@Parameter(
		ref = "assetid"
	) @PathParam("assetid") int assetId, @Parameter(
		description = "whether to include confirmed, unconfirmed or both",
		required = true
	) @QueryParam("confirmationStatus") ConfirmationStatus confirmationStatus, @Parameter(
		ref = "limit"
	) @QueryParam("limit") Integer limit, @Parameter(
		ref = "offset"
	) @QueryParam("offset") Integer offset, @Parameter(
		ref = "reverse"
	) @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			if (!repository.getAssetRepository().assetExists(assetId))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			return repository.getTransactionRepository().getAssetTransactions(assetId, confirmationStatus, limit, offset, reverse);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@GET
	@Path("/transfers/{assetid}")
	@Operation(
		summary = "Asset transfers for specific asset, with optional address filter",
		responses = {
			@ApiResponse(
				description = "Asset transactions",
				content = @Content(
					array = @ArraySchema(
						schema = @Schema(
							implementation = TransferAssetTransactionData.class
						)
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.INVALID_ADDRESS, ApiError.INVALID_ASSET_ID, ApiError.REPOSITORY_ISSUE
	})
	public List<TransferAssetTransactionData> getAssetTransfers(@Parameter(
		ref = "assetid"
	) @PathParam("assetid") int assetId, @QueryParam("address") String address, @Parameter(
		ref = "limit"
	) @QueryParam("limit") Integer limit, @Parameter(
		ref = "offset"
	) @QueryParam("offset") Integer offset, @Parameter(
		ref = "reverse"
	) @QueryParam("reverse") Boolean reverse) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			if (!repository.getAssetRepository().assetExists(assetId))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ASSET_ID);

			if (address != null && !Crypto.isValidAddress(address))
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);

			return repository.getTransactionRepository().getAssetTransfers(assetId, address, limit, offset, reverse);
		} catch (ApiException e) {
			throw e;
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/order/delete")
	@Operation(
		summary = "Cancel existing asset order",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CancelAssetOrderTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, CANCEL_ORDER transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.NON_PRODUCTION, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE, ApiError.TRANSACTION_INVALID
	})
	public String cancelOrder(CancelAssetOrderTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = CancelAssetOrderTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/issue")
	@Operation(
		summary = "Issue new asset",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = IssueAssetTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, ISSUE_ASSET transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.NON_PRODUCTION, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE, ApiError.TRANSACTION_INVALID
	})
	public String issueAsset(IssueAssetTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = IssueAssetTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/order")
	@Operation(
		summary = "Create asset order",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = CreateAssetOrderTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, CREATE_ORDER transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.NON_PRODUCTION, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE, ApiError.TRANSACTION_INVALID
	})
	public String createOrder(CreateAssetOrderTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = CreateAssetOrderTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/update")
	@Operation(
		summary = "Update asset",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = UpdateAssetTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, UPDATE_ASSET transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.NON_PRODUCTION, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE, ApiError.TRANSACTION_INVALID
	})
	public String updateAsset(UpdateAssetTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = UpdateAssetTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

	@POST
	@Path("/transfer")
	@Operation(
		summary = "Transfer quantity of asset",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = TransferAssetTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned, TRANSFER_ASSET transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	@ApiErrors({
		ApiError.NON_PRODUCTION, ApiError.TRANSFORMATION_ERROR, ApiError.REPOSITORY_ISSUE, ApiError.TRANSACTION_INVALID
	})
	public String transferAsset(TransferAssetTransactionData transactionData) {
		if (Settings.getInstance().isApiRestricted())
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.NON_PRODUCTION);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValidUnconfirmed();
			if (result != ValidationResult.OK)
				throw TransactionsResource.createTransactionInvalidException(request, result);

			byte[] bytes = TransferAssetTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.TRANSFORMATION_ERROR, e);
		} catch (DataException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
		}
	}

}
