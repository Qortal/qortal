package org.qortal.controller;

import cash.z.wallet.sdk.rpc.CompactFormats;
import cash.z.wallet.sdk.rpc.CompactTxStreamerGrpc;
import cash.z.wallet.sdk.rpc.Service;
import com.google.common.hash.HashCode;
import com.google.protobuf.ByteString;
import io.grpc.HandlerRegistry;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only, loopback-bound lightwalletd fixture shared by the Java Core client
 * and the Pirate Unified native library. The two clients use wire-compatible
 * messages but different gRPC service names, so both names are registered.
 */
final class PirateUnifiedLoopbackLightwalletd implements AutoCloseable {

	static final long SAPLING_ACTIVATION_HEIGHT = 152_855L;
	static final long HISTORICAL_NOTE_HEIGHT = SAPLING_ACTIVATION_HEIGHT + 1L;
	static final long HISTORICAL_NOTE_VALUE = 123_456_789L;
	static final long TIP_HEIGHT = 152_858L;
	static final long IRONWOOD_PROBE_HEIGHT = TIP_HEIGHT - 30L;

	static final String CASH_SERVICE = CompactTxStreamerGrpc.SERVICE_NAME;
	static final String PIRATE_SERVICE = "pirate.wallet.sdk.rpc.CompactTxStreamer";

	private static final int FIRST_BLOCK_TIME = 1_534_262_400;
	// Generated offline from the deterministic entropy-7 Qortal JNI wallet with the pinned
	// Pirate Unified v1.1.7 Rust sources. These public test-vector bytes carry no production key.
	private static final String HISTORICAL_TX_HASH =
			"5dfe16939ede32a097e28d225880e8b6a661bdaf64f5cb17c559bd2a57bb7a19";
	private static final String HISTORICAL_CMU =
			"0bf391fbcf3c9e16a2f7d3840120c884b6663d41fcba373b7cf9c49069a5501f";
	private static final String HISTORICAL_EPK =
			"29e7d3811a94f4df4c025ed50d79df089a160b229c60c407b2bea018aa3bd185";
	private static final String HISTORICAL_CIPHERTEXT =
			"0b8fcb16806b6ef2161e09569f608883c0a83b956796324b227082e5046cb646"
					+ "5a6bc8f55f5ea675ce790dff7d9927a0f74d2d4b";

	private final Map<String, AtomicInteger> rpcCounts = new ConcurrentHashMap<>();
	private final List<String> observedRanges = Collections.synchronizedList(new ArrayList<>());
	private final AtomicInteger forbiddenRpcCount = new AtomicInteger();
	private final AtomicInteger unexpectedRpcCount = new AtomicInteger();
	private final AtomicInteger activationProbeCount = new AtomicInteger();
	private final AtomicInteger subtreeProbeCount = new AtomicInteger();
	private final AtomicInteger cashCompleteRangeCount = new AtomicInteger();
	private final AtomicInteger pirateCompleteRangeCount = new AtomicInteger();
	private final AtomicInteger pirateTipRangeCount = new AtomicInteger();
	private final AtomicInteger pirateScannedBlockCount = new AtomicInteger();
	private final Server server;
	private final String cashChainName;
	private final String pirateChainName;
	private final boolean includeHistoricalNote;

	PirateUnifiedLoopbackLightwalletd() throws IOException {
		this(0, "main", "main", false);
	}

	PirateUnifiedLoopbackLightwalletd(boolean includeHistoricalNote) throws IOException {
		this(0, "main", "main", includeHistoricalNote);
	}

	PirateUnifiedLoopbackLightwalletd(int port, String chainName) throws IOException {
		this(port, chainName, chainName, false);
	}

	PirateUnifiedLoopbackLightwalletd(int port, String cashChainName, String pirateChainName) throws IOException {
		this(port, cashChainName, pirateChainName, false);
	}

	private PirateUnifiedLoopbackLightwalletd(int port, String cashChainName, String pirateChainName,
			boolean includeHistoricalNote) throws IOException {
		if (port < 0 || port > 65_535)
			throw new IllegalArgumentException("Invalid fixture port");
		if (cashChainName == null || cashChainName.isBlank()
				|| pirateChainName == null || pirateChainName.isBlank())
			throw new IllegalArgumentException("Missing fixture chain name");

		this.cashChainName = cashChainName;
		this.pirateChainName = pirateChainName;
		this.includeHistoricalNote = includeHistoricalNote;
		FixtureService fixtureService = new FixtureService();
		ServerInterceptor auditInterceptor = this::auditCall;
		this.server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", port))
							  .addService(ServerInterceptors.intercept(fixtureService, auditInterceptor))
							  .addService(ServerInterceptors.intercept(pirateService(fixtureService), auditInterceptor))
							  .fallbackHandlerRegistry(new UnknownMethodRegistry())
							  .build()
							  .start();
	}

	String endpoint() {
		return "http://127.0.0.1:" + this.server.getPort() + "/";
	}

	int rpcCount(String service, String method) {
		AtomicInteger count = this.rpcCounts.get(MethodDescriptor.generateFullMethodName(service, method));
		return count == null ? 0 : count.get();
	}

	int completeRangeCount(String service) {
		return PIRATE_SERVICE.equals(service)
				? this.pirateCompleteRangeCount.get()
				: this.cashCompleteRangeCount.get();
	}

	int pirateTipRangeCount() {
		return this.pirateTipRangeCount.get();
	}

	int pirateScannedBlockCount() {
		return this.pirateScannedBlockCount.get();
	}

	List<String> observedRanges() {
		synchronized (this.observedRanges) {
			return List.copyOf(this.observedRanges);
		}
	}

	int forbiddenRpcCount() {
		return this.forbiddenRpcCount.get();
	}

	int unexpectedRpcCount() {
		return this.unexpectedRpcCount.get();
	}

	int activationProbeCount() {
		return this.activationProbeCount.get();
	}

	int subtreeProbeCount() {
		return this.subtreeProbeCount.get();
	}

	@Override
	public void close() throws InterruptedException {
		this.server.shutdownNow();
		if (!this.server.awaitTermination(10, TimeUnit.SECONDS))
			throw new IllegalStateException("Loopback lightwalletd did not stop");
	}

	private <ReqT, RespT> ServerCall.Listener<ReqT> auditCall(
			ServerCall<ReqT, RespT> call, io.grpc.Metadata headers, ServerCallHandler<ReqT, RespT> next) {
		recordRpc(call.getMethodDescriptor().getFullMethodName());
		return next.startCall(call, headers);
	}

	private void recordRpc(String fullMethodName) {
		this.rpcCounts.computeIfAbsent(fullMethodName, ignored -> new AtomicInteger()).incrementAndGet();
		int separator = fullMethodName.lastIndexOf('/');
		String service = separator < 0 ? "" : fullMethodName.substring(0, separator);
		String method = separator < 0 ? fullMethodName : fullMethodName.substring(separator + 1);
		boolean knownService = CASH_SERVICE.equals(service) || PIRATE_SERVICE.equals(service);
		if (knownService && ("GetLatestBlock".equals(method) || "GetBlock".equals(method)
				|| "GetBlockRange".equals(method) || "GetLightdInfo".equals(method)))
			return;

		if (knownService && ("GetTransaction".equals(method) || "SendTransaction".equals(method))) {
			this.forbiddenRpcCount.incrementAndGet();
			return;
		}

		if (PIRATE_SERVICE.equals(service) && "GetSubtreeRoots".equals(method)) {
			this.subtreeProbeCount.incrementAndGet();
			return;
		}

		this.unexpectedRpcCount.incrementAndGet();
	}

	private ServerServiceDefinition pirateService(FixtureService fixtureService) {
		MethodDescriptor<Service.ChainSpec, Service.BlockID> latest =
				rename(CompactTxStreamerGrpc.getGetLatestBlockMethod(), "GetLatestBlock");
		MethodDescriptor<Service.BlockID, CompactFormats.CompactBlock> block =
				rename(CompactTxStreamerGrpc.getGetBlockMethod(), "GetBlock");
		MethodDescriptor<Service.BlockRange, CompactFormats.CompactBlock> range =
				rename(CompactTxStreamerGrpc.getGetBlockRangeMethod(), "GetBlockRange");
		MethodDescriptor<Service.Empty, Service.LightdInfo> info =
				rename(CompactTxStreamerGrpc.getGetLightdInfoMethod(), "GetLightdInfo");
		MethodDescriptor<Service.TxFilter, Service.RawTransaction> transaction =
				rename(CompactTxStreamerGrpc.getGetTransactionMethod(), "GetTransaction");
		MethodDescriptor<Service.RawTransaction, Service.SendResponse> send =
				rename(CompactTxStreamerGrpc.getSendTransactionMethod(), "SendTransaction");
		MethodDescriptor<Service.BlockID, Service.TreeState> tree =
				rename(CompactTxStreamerGrpc.getGetTreeStateMethod(), "GetTreeState");

		return ServerServiceDefinition.builder(PIRATE_SERVICE)
				.addMethod(latest, ServerCalls.asyncUnaryCall(fixtureService::getLatestBlock))
				.addMethod(block, ServerCalls.asyncUnaryCall(fixtureService::getPirateBlock))
				.addMethod(range, ServerCalls.asyncServerStreamingCall(
						(request, observer) -> fixtureService.getBlockRange(PIRATE_SERVICE, request, observer)))
				.addMethod(info, ServerCalls.asyncUnaryCall(fixtureService::getPirateLightdInfo))
				.addMethod(transaction, ServerCalls.asyncUnaryCall(fixtureService::getTransaction))
				.addMethod(send, ServerCalls.asyncUnaryCall(fixtureService::sendTransaction))
				.addMethod(tree, ServerCalls.asyncUnaryCall(fixtureService::getTreeState))
				.build();
	}

	private static <ReqT, RespT> MethodDescriptor<ReqT, RespT> rename(
			MethodDescriptor<ReqT, RespT> source, String method) {
		return source.toBuilder()
				.setFullMethodName(MethodDescriptor.generateFullMethodName(PIRATE_SERVICE, method))
				.setSchemaDescriptor(null)
				.build();
	}

	private final class FixtureService extends CompactTxStreamerGrpc.CompactTxStreamerImplBase {

		@Override
		public void getLatestBlock(Service.ChainSpec request, StreamObserver<Service.BlockID> observer) {
			respond(observer,
					Service.BlockID.newBuilder()
							.setHeight(TIP_HEIGHT)
							.setHash(ByteString.copyFrom(blockHash(TIP_HEIGHT)))
							.build());
		}

		@Override
		public void getBlock(Service.BlockID request, StreamObserver<CompactFormats.CompactBlock> observer) {
			getBlock(false, request, observer);
		}

		private void getPirateBlock(Service.BlockID request,
				StreamObserver<CompactFormats.CompactBlock> observer) {
			getBlock(true, request, observer);
		}

		private void getBlock(boolean pirateService, Service.BlockID request,
				StreamObserver<CompactFormats.CompactBlock> observer) {
			long height = request.getHeight();
			if (height == IRONWOOD_PROBE_HEIGHT) {
				activationProbeCount.incrementAndGet();
				respond(observer, block(height));
				return;
			}
			if (height < SAPLING_ACTIVATION_HEIGHT || height > TIP_HEIGHT) {
				fail(observer, Status.INVALID_ARGUMENT.withDescription("Block is outside the deterministic fixture"));
				return;
			}
			if (pirateService)
				pirateScannedBlockCount.incrementAndGet();
			respond(observer, block(height));
		}

		@Override
		public void getBlockRange(Service.BlockRange request, StreamObserver<CompactFormats.CompactBlock> observer) {
			getBlockRange(CASH_SERVICE, request, observer);
		}

		private void getBlockRange(String service, Service.BlockRange request,
				StreamObserver<CompactFormats.CompactBlock> observer) {
			long start = request.getStart().getHeight();
			long end = request.getEnd().getHeight();
			observedRanges.add(service + ":" + start + "-" + end);
			if (start < SAPLING_ACTIVATION_HEIGHT || end > TIP_HEIGHT || start > end) {
				fail(observer, Status.INVALID_ARGUMENT.withDescription("Range is outside the deterministic fixture"));
				return;
			}
			for (long height = start; height <= end; height++)
				observer.onNext(block(height));
			if (start == SAPLING_ACTIVATION_HEIGHT && end == TIP_HEIGHT) {
				AtomicInteger counter = PIRATE_SERVICE.equals(service)
						? pirateCompleteRangeCount : cashCompleteRangeCount;
				counter.incrementAndGet();
			}
			if (PIRATE_SERVICE.equals(service) && end == TIP_HEIGHT)
				pirateTipRangeCount.incrementAndGet();
			observer.onCompleted();
		}

		@Override
		public void getLightdInfo(Service.Empty request, StreamObserver<Service.LightdInfo> observer) {
			getLightdInfo(cashChainName, observer);
		}

		private void getPirateLightdInfo(Service.Empty request, StreamObserver<Service.LightdInfo> observer) {
			getLightdInfo(pirateChainName, observer);
		}

		private void getLightdInfo(String chainName, StreamObserver<Service.LightdInfo> observer) {
			respond(observer,
					Service.LightdInfo.newBuilder()
							.setVersion("qortal-loopback-fixture")
							.setVendor("Qortal test fixture")
							.setChainName(chainName)
							.setSaplingActivationHeight(SAPLING_ACTIVATION_HEIGHT)
							.setConsensusBranchId("76b809bb")
							.setBlockHeight(TIP_HEIGHT)
							.setEstimatedHeight(TIP_HEIGHT)
							.build());
		}

		@Override
		public void getTransaction(Service.TxFilter request, StreamObserver<Service.RawTransaction> observer) {
			forbidden(observer, "Transaction reads are outside the empty-chain fixture");
		}

		@Override
		public void sendTransaction(Service.RawTransaction request, StreamObserver<Service.SendResponse> observer) {
			forbidden(observer, "Transaction broadcast is forbidden in this fixture");
		}

		@Override
		public void getTreeState(Service.BlockID request, StreamObserver<Service.TreeState> observer) {
			fail(observer,
					Status.FAILED_PRECONDITION.withDescription("The activation-boundary fixture must not require a "
							+ "remote tree state"));
		}
	}

	private final class UnknownMethodRegistry extends HandlerRegistry {
		@Override
		public ServerMethodDefinition<?, ?> lookupMethod(String methodName, String authority) {
			recordRpc(methodName);
			return null;
		}

		@Override
		public List<ServerServiceDefinition> getServices() {
			return List.of();
		}
	}

	private CompactFormats.CompactBlock block(long height) {
		CompactFormats.CompactBlock.Builder block = CompactFormats.CompactBlock.newBuilder()
				.setProtoVersion(4)
				.setHeight(height)
				.setHash(ByteString.copyFrom(blockHash(height)))
				.setPrevHash(ByteString.copyFrom(blockHash(height - 1)))
				.setTime(FIRST_BLOCK_TIME + Math.toIntExact((height - SAPLING_ACTIVATION_HEIGHT) * 60L));
		if (this.includeHistoricalNote && height == HISTORICAL_NOTE_HEIGHT)
			block.addVtx(CompactFormats.CompactTx.newBuilder()
					.setIndex(0)
					.setHash(ByteString.copyFrom(HashCode.fromString(HISTORICAL_TX_HASH).asBytes()))
					.addOutputs(CompactFormats.CompactOutput.newBuilder()
							.setCmu(ByteString.copyFrom(HashCode.fromString(HISTORICAL_CMU).asBytes()))
							.setEpk(ByteString.copyFrom(HashCode.fromString(HISTORICAL_EPK).asBytes()))
							.setCiphertext(ByteString.copyFrom(HashCode.fromString(HISTORICAL_CIPHERTEXT).asBytes()))));
		return block.build();
	}

	private static byte[] blockHash(long height) {
		byte[] hash = new byte[32];
		ByteBuffer.wrap(hash).putLong(24, height);
		return hash;
	}

	private static <T> void respond(StreamObserver<T> observer, T response) {
		observer.onNext(response);
		observer.onCompleted();
	}

	private static void fail(StreamObserver<?> observer, Status status) {
		observer.onError(status.asRuntimeException());
	}

	private void forbidden(StreamObserver<?> observer, String description) {
		fail(observer, Status.PERMISSION_DENIED.withDescription(description));
	}
}
