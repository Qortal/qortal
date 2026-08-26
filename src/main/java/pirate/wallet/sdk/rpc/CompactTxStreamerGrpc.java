package pirate.wallet.sdk.rpc;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.68.1)",
    comments = "Source: service.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class CompactTxStreamerGrpc {

  private CompactTxStreamerGrpc() {}

  public static final java.lang.String SERVICE_NAME = "pirate.wallet.sdk.rpc.CompactTxStreamer";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.BlockID,
      pirate.wallet.sdk.rpc.Service.BlockID> getGetLiteWalletBlockGroupMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetLiteWalletBlockGroup",
      requestType = pirate.wallet.sdk.rpc.Service.BlockID.class,
      responseType = pirate.wallet.sdk.rpc.Service.BlockID.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.BlockID,
      pirate.wallet.sdk.rpc.Service.BlockID> getGetLiteWalletBlockGroupMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.BlockID, pirate.wallet.sdk.rpc.Service.BlockID> getGetLiteWalletBlockGroupMethod;
    if ((getGetLiteWalletBlockGroupMethod = CompactTxStreamerGrpc.getGetLiteWalletBlockGroupMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getGetLiteWalletBlockGroupMethod = CompactTxStreamerGrpc.getGetLiteWalletBlockGroupMethod) == null) {
          CompactTxStreamerGrpc.getGetLiteWalletBlockGroupMethod = getGetLiteWalletBlockGroupMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.BlockID, pirate.wallet.sdk.rpc.Service.BlockID>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetLiteWalletBlockGroup"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.BlockID.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.BlockID.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("GetLiteWalletBlockGroup"))
              .build();
        }
      }
    }
    return getGetLiteWalletBlockGroupMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.ChainSpec,
      pirate.wallet.sdk.rpc.Service.BlockID> getGetLatestBlockMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetLatestBlock",
      requestType = pirate.wallet.sdk.rpc.Service.ChainSpec.class,
      responseType = pirate.wallet.sdk.rpc.Service.BlockID.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.ChainSpec,
      pirate.wallet.sdk.rpc.Service.BlockID> getGetLatestBlockMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.ChainSpec, pirate.wallet.sdk.rpc.Service.BlockID> getGetLatestBlockMethod;
    if ((getGetLatestBlockMethod = CompactTxStreamerGrpc.getGetLatestBlockMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getGetLatestBlockMethod = CompactTxStreamerGrpc.getGetLatestBlockMethod) == null) {
          CompactTxStreamerGrpc.getGetLatestBlockMethod = getGetLatestBlockMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.ChainSpec, pirate.wallet.sdk.rpc.Service.BlockID>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetLatestBlock"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.ChainSpec.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.BlockID.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("GetLatestBlock"))
              .build();
        }
      }
    }
    return getGetLatestBlockMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.BlockID,
      pirate.wallet.sdk.rpc.CompactFormats.CompactBlock> getGetBlockMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetBlock",
      requestType = pirate.wallet.sdk.rpc.Service.BlockID.class,
      responseType = pirate.wallet.sdk.rpc.CompactFormats.CompactBlock.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.BlockID,
      pirate.wallet.sdk.rpc.CompactFormats.CompactBlock> getGetBlockMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.BlockID, pirate.wallet.sdk.rpc.CompactFormats.CompactBlock> getGetBlockMethod;
    if ((getGetBlockMethod = CompactTxStreamerGrpc.getGetBlockMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getGetBlockMethod = CompactTxStreamerGrpc.getGetBlockMethod) == null) {
          CompactTxStreamerGrpc.getGetBlockMethod = getGetBlockMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.BlockID, pirate.wallet.sdk.rpc.CompactFormats.CompactBlock>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetBlock"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.BlockID.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.CompactFormats.CompactBlock.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("GetBlock"))
              .build();
        }
      }
    }
    return getGetBlockMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.BlockRange,
      pirate.wallet.sdk.rpc.CompactFormats.CompactBlock> getGetBlockRangeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetBlockRange",
      requestType = pirate.wallet.sdk.rpc.Service.BlockRange.class,
      responseType = pirate.wallet.sdk.rpc.CompactFormats.CompactBlock.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.BlockRange,
      pirate.wallet.sdk.rpc.CompactFormats.CompactBlock> getGetBlockRangeMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.BlockRange, pirate.wallet.sdk.rpc.CompactFormats.CompactBlock> getGetBlockRangeMethod;
    if ((getGetBlockRangeMethod = CompactTxStreamerGrpc.getGetBlockRangeMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getGetBlockRangeMethod = CompactTxStreamerGrpc.getGetBlockRangeMethod) == null) {
          CompactTxStreamerGrpc.getGetBlockRangeMethod = getGetBlockRangeMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.BlockRange, pirate.wallet.sdk.rpc.CompactFormats.CompactBlock>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetBlockRange"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.BlockRange.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.CompactFormats.CompactBlock.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("GetBlockRange"))
              .build();
        }
      }
    }
    return getGetBlockRangeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.PriceRequest,
      pirate.wallet.sdk.rpc.Service.PriceResponse> getGetARRRPriceMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetARRRPrice",
      requestType = pirate.wallet.sdk.rpc.Service.PriceRequest.class,
      responseType = pirate.wallet.sdk.rpc.Service.PriceResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.PriceRequest,
      pirate.wallet.sdk.rpc.Service.PriceResponse> getGetARRRPriceMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.PriceRequest, pirate.wallet.sdk.rpc.Service.PriceResponse> getGetARRRPriceMethod;
    if ((getGetARRRPriceMethod = CompactTxStreamerGrpc.getGetARRRPriceMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getGetARRRPriceMethod = CompactTxStreamerGrpc.getGetARRRPriceMethod) == null) {
          CompactTxStreamerGrpc.getGetARRRPriceMethod = getGetARRRPriceMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.PriceRequest, pirate.wallet.sdk.rpc.Service.PriceResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetARRRPrice"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.PriceRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.PriceResponse.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("GetARRRPrice"))
              .build();
        }
      }
    }
    return getGetARRRPriceMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Empty,
      pirate.wallet.sdk.rpc.Service.PriceResponse> getGetCurrentARRRPriceMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetCurrentARRRPrice",
      requestType = pirate.wallet.sdk.rpc.Service.Empty.class,
      responseType = pirate.wallet.sdk.rpc.Service.PriceResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Empty,
      pirate.wallet.sdk.rpc.Service.PriceResponse> getGetCurrentARRRPriceMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Empty, pirate.wallet.sdk.rpc.Service.PriceResponse> getGetCurrentARRRPriceMethod;
    if ((getGetCurrentARRRPriceMethod = CompactTxStreamerGrpc.getGetCurrentARRRPriceMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getGetCurrentARRRPriceMethod = CompactTxStreamerGrpc.getGetCurrentARRRPriceMethod) == null) {
          CompactTxStreamerGrpc.getGetCurrentARRRPriceMethod = getGetCurrentARRRPriceMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.Empty, pirate.wallet.sdk.rpc.Service.PriceResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetCurrentARRRPrice"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.PriceResponse.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("GetCurrentARRRPrice"))
              .build();
        }
      }
    }
    return getGetCurrentARRRPriceMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.TxFilter,
      pirate.wallet.sdk.rpc.Service.RawTransaction> getGetTransactionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTransaction",
      requestType = pirate.wallet.sdk.rpc.Service.TxFilter.class,
      responseType = pirate.wallet.sdk.rpc.Service.RawTransaction.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.TxFilter,
      pirate.wallet.sdk.rpc.Service.RawTransaction> getGetTransactionMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.TxFilter, pirate.wallet.sdk.rpc.Service.RawTransaction> getGetTransactionMethod;
    if ((getGetTransactionMethod = CompactTxStreamerGrpc.getGetTransactionMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getGetTransactionMethod = CompactTxStreamerGrpc.getGetTransactionMethod) == null) {
          CompactTxStreamerGrpc.getGetTransactionMethod = getGetTransactionMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.TxFilter, pirate.wallet.sdk.rpc.Service.RawTransaction>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTransaction"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.TxFilter.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.RawTransaction.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("GetTransaction"))
              .build();
        }
      }
    }
    return getGetTransactionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.RawTransaction,
      pirate.wallet.sdk.rpc.Service.SendResponse> getSendTransactionMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SendTransaction",
      requestType = pirate.wallet.sdk.rpc.Service.RawTransaction.class,
      responseType = pirate.wallet.sdk.rpc.Service.SendResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.RawTransaction,
      pirate.wallet.sdk.rpc.Service.SendResponse> getSendTransactionMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.RawTransaction, pirate.wallet.sdk.rpc.Service.SendResponse> getSendTransactionMethod;
    if ((getSendTransactionMethod = CompactTxStreamerGrpc.getSendTransactionMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getSendTransactionMethod = CompactTxStreamerGrpc.getSendTransactionMethod) == null) {
          CompactTxStreamerGrpc.getSendTransactionMethod = getSendTransactionMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.RawTransaction, pirate.wallet.sdk.rpc.Service.SendResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SendTransaction"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.RawTransaction.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.SendResponse.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("SendTransaction"))
              .build();
        }
      }
    }
    return getSendTransactionMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter,
      pirate.wallet.sdk.rpc.Service.RawTransaction> getGetTaddressTxidsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTaddressTxids",
      requestType = pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter.class,
      responseType = pirate.wallet.sdk.rpc.Service.RawTransaction.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter,
      pirate.wallet.sdk.rpc.Service.RawTransaction> getGetTaddressTxidsMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter, pirate.wallet.sdk.rpc.Service.RawTransaction> getGetTaddressTxidsMethod;
    if ((getGetTaddressTxidsMethod = CompactTxStreamerGrpc.getGetTaddressTxidsMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getGetTaddressTxidsMethod = CompactTxStreamerGrpc.getGetTaddressTxidsMethod) == null) {
          CompactTxStreamerGrpc.getGetTaddressTxidsMethod = getGetTaddressTxidsMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter, pirate.wallet.sdk.rpc.Service.RawTransaction>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTaddressTxids"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.RawTransaction.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("GetTaddressTxids"))
              .build();
        }
      }
    }
    return getGetTaddressTxidsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter,
      pirate.wallet.sdk.rpc.Service.RawTransaction> getGetAddressTxidsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetAddressTxids",
      requestType = pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter.class,
      responseType = pirate.wallet.sdk.rpc.Service.RawTransaction.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter,
      pirate.wallet.sdk.rpc.Service.RawTransaction> getGetAddressTxidsMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter, pirate.wallet.sdk.rpc.Service.RawTransaction> getGetAddressTxidsMethod;
    if ((getGetAddressTxidsMethod = CompactTxStreamerGrpc.getGetAddressTxidsMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getGetAddressTxidsMethod = CompactTxStreamerGrpc.getGetAddressTxidsMethod) == null) {
          CompactTxStreamerGrpc.getGetAddressTxidsMethod = getGetAddressTxidsMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter, pirate.wallet.sdk.rpc.Service.RawTransaction>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetAddressTxids"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.RawTransaction.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("GetAddressTxids"))
              .build();
        }
      }
    }
    return getGetAddressTxidsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.AddressList,
      pirate.wallet.sdk.rpc.Service.Balance> getGetTaddressBalanceMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTaddressBalance",
      requestType = pirate.wallet.sdk.rpc.Service.AddressList.class,
      responseType = pirate.wallet.sdk.rpc.Service.Balance.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.AddressList,
      pirate.wallet.sdk.rpc.Service.Balance> getGetTaddressBalanceMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.AddressList, pirate.wallet.sdk.rpc.Service.Balance> getGetTaddressBalanceMethod;
    if ((getGetTaddressBalanceMethod = CompactTxStreamerGrpc.getGetTaddressBalanceMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getGetTaddressBalanceMethod = CompactTxStreamerGrpc.getGetTaddressBalanceMethod) == null) {
          CompactTxStreamerGrpc.getGetTaddressBalanceMethod = getGetTaddressBalanceMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.AddressList, pirate.wallet.sdk.rpc.Service.Balance>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTaddressBalance"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.AddressList.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.Balance.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("GetTaddressBalance"))
              .build();
        }
      }
    }
    return getGetTaddressBalanceMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Address,
      pirate.wallet.sdk.rpc.Service.Balance> getGetTaddressBalanceStreamMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTaddressBalanceStream",
      requestType = pirate.wallet.sdk.rpc.Service.Address.class,
      responseType = pirate.wallet.sdk.rpc.Service.Balance.class,
      methodType = io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Address,
      pirate.wallet.sdk.rpc.Service.Balance> getGetTaddressBalanceStreamMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Address, pirate.wallet.sdk.rpc.Service.Balance> getGetTaddressBalanceStreamMethod;
    if ((getGetTaddressBalanceStreamMethod = CompactTxStreamerGrpc.getGetTaddressBalanceStreamMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getGetTaddressBalanceStreamMethod = CompactTxStreamerGrpc.getGetTaddressBalanceStreamMethod) == null) {
          CompactTxStreamerGrpc.getGetTaddressBalanceStreamMethod = getGetTaddressBalanceStreamMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.Address, pirate.wallet.sdk.rpc.Service.Balance>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTaddressBalanceStream"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.Address.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.Balance.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("GetTaddressBalanceStream"))
              .build();
        }
      }
    }
    return getGetTaddressBalanceStreamMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Exclude,
      pirate.wallet.sdk.rpc.CompactFormats.CompactTx> getGetMempoolTxMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetMempoolTx",
      requestType = pirate.wallet.sdk.rpc.Service.Exclude.class,
      responseType = pirate.wallet.sdk.rpc.CompactFormats.CompactTx.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Exclude,
      pirate.wallet.sdk.rpc.CompactFormats.CompactTx> getGetMempoolTxMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Exclude, pirate.wallet.sdk.rpc.CompactFormats.CompactTx> getGetMempoolTxMethod;
    if ((getGetMempoolTxMethod = CompactTxStreamerGrpc.getGetMempoolTxMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getGetMempoolTxMethod = CompactTxStreamerGrpc.getGetMempoolTxMethod) == null) {
          CompactTxStreamerGrpc.getGetMempoolTxMethod = getGetMempoolTxMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.Exclude, pirate.wallet.sdk.rpc.CompactFormats.CompactTx>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetMempoolTx"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.Exclude.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.CompactFormats.CompactTx.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("GetMempoolTx"))
              .build();
        }
      }
    }
    return getGetMempoolTxMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Empty,
      pirate.wallet.sdk.rpc.Service.RawTransaction> getGetMempoolStreamMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetMempoolStream",
      requestType = pirate.wallet.sdk.rpc.Service.Empty.class,
      responseType = pirate.wallet.sdk.rpc.Service.RawTransaction.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Empty,
      pirate.wallet.sdk.rpc.Service.RawTransaction> getGetMempoolStreamMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Empty, pirate.wallet.sdk.rpc.Service.RawTransaction> getGetMempoolStreamMethod;
    if ((getGetMempoolStreamMethod = CompactTxStreamerGrpc.getGetMempoolStreamMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getGetMempoolStreamMethod = CompactTxStreamerGrpc.getGetMempoolStreamMethod) == null) {
          CompactTxStreamerGrpc.getGetMempoolStreamMethod = getGetMempoolStreamMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.Empty, pirate.wallet.sdk.rpc.Service.RawTransaction>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetMempoolStream"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.RawTransaction.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("GetMempoolStream"))
              .build();
        }
      }
    }
    return getGetMempoolStreamMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.BlockID,
      pirate.wallet.sdk.rpc.Service.TreeState> getGetTreeStateMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetTreeState",
      requestType = pirate.wallet.sdk.rpc.Service.BlockID.class,
      responseType = pirate.wallet.sdk.rpc.Service.TreeState.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.BlockID,
      pirate.wallet.sdk.rpc.Service.TreeState> getGetTreeStateMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.BlockID, pirate.wallet.sdk.rpc.Service.TreeState> getGetTreeStateMethod;
    if ((getGetTreeStateMethod = CompactTxStreamerGrpc.getGetTreeStateMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getGetTreeStateMethod = CompactTxStreamerGrpc.getGetTreeStateMethod) == null) {
          CompactTxStreamerGrpc.getGetTreeStateMethod = getGetTreeStateMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.BlockID, pirate.wallet.sdk.rpc.Service.TreeState>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetTreeState"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.BlockID.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.TreeState.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("GetTreeState"))
              .build();
        }
      }
    }
    return getGetTreeStateMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg,
      pirate.wallet.sdk.rpc.Service.GetAddressUtxosReplyList> getGetAddressUtxosMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetAddressUtxos",
      requestType = pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg.class,
      responseType = pirate.wallet.sdk.rpc.Service.GetAddressUtxosReplyList.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg,
      pirate.wallet.sdk.rpc.Service.GetAddressUtxosReplyList> getGetAddressUtxosMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg, pirate.wallet.sdk.rpc.Service.GetAddressUtxosReplyList> getGetAddressUtxosMethod;
    if ((getGetAddressUtxosMethod = CompactTxStreamerGrpc.getGetAddressUtxosMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getGetAddressUtxosMethod = CompactTxStreamerGrpc.getGetAddressUtxosMethod) == null) {
          CompactTxStreamerGrpc.getGetAddressUtxosMethod = getGetAddressUtxosMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg, pirate.wallet.sdk.rpc.Service.GetAddressUtxosReplyList>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetAddressUtxos"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.GetAddressUtxosReplyList.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("GetAddressUtxos"))
              .build();
        }
      }
    }
    return getGetAddressUtxosMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg,
      pirate.wallet.sdk.rpc.Service.GetAddressUtxosReply> getGetAddressUtxosStreamMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetAddressUtxosStream",
      requestType = pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg.class,
      responseType = pirate.wallet.sdk.rpc.Service.GetAddressUtxosReply.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg,
      pirate.wallet.sdk.rpc.Service.GetAddressUtxosReply> getGetAddressUtxosStreamMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg, pirate.wallet.sdk.rpc.Service.GetAddressUtxosReply> getGetAddressUtxosStreamMethod;
    if ((getGetAddressUtxosStreamMethod = CompactTxStreamerGrpc.getGetAddressUtxosStreamMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getGetAddressUtxosStreamMethod = CompactTxStreamerGrpc.getGetAddressUtxosStreamMethod) == null) {
          CompactTxStreamerGrpc.getGetAddressUtxosStreamMethod = getGetAddressUtxosStreamMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg, pirate.wallet.sdk.rpc.Service.GetAddressUtxosReply>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetAddressUtxosStream"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.GetAddressUtxosReply.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("GetAddressUtxosStream"))
              .build();
        }
      }
    }
    return getGetAddressUtxosStreamMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Empty,
      pirate.wallet.sdk.rpc.Service.LightdInfo> getGetLightdInfoMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetLightdInfo",
      requestType = pirate.wallet.sdk.rpc.Service.Empty.class,
      responseType = pirate.wallet.sdk.rpc.Service.LightdInfo.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Empty,
      pirate.wallet.sdk.rpc.Service.LightdInfo> getGetLightdInfoMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Empty, pirate.wallet.sdk.rpc.Service.LightdInfo> getGetLightdInfoMethod;
    if ((getGetLightdInfoMethod = CompactTxStreamerGrpc.getGetLightdInfoMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getGetLightdInfoMethod = CompactTxStreamerGrpc.getGetLightdInfoMethod) == null) {
          CompactTxStreamerGrpc.getGetLightdInfoMethod = getGetLightdInfoMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.Empty, pirate.wallet.sdk.rpc.Service.LightdInfo>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetLightdInfo"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.Empty.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.LightdInfo.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("GetLightdInfo"))
              .build();
        }
      }
    }
    return getGetLightdInfoMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Duration,
      pirate.wallet.sdk.rpc.Service.PingResponse> getPingMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Ping",
      requestType = pirate.wallet.sdk.rpc.Service.Duration.class,
      responseType = pirate.wallet.sdk.rpc.Service.PingResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Duration,
      pirate.wallet.sdk.rpc.Service.PingResponse> getPingMethod() {
    io.grpc.MethodDescriptor<pirate.wallet.sdk.rpc.Service.Duration, pirate.wallet.sdk.rpc.Service.PingResponse> getPingMethod;
    if ((getPingMethod = CompactTxStreamerGrpc.getPingMethod) == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        if ((getPingMethod = CompactTxStreamerGrpc.getPingMethod) == null) {
          CompactTxStreamerGrpc.getPingMethod = getPingMethod =
              io.grpc.MethodDescriptor.<pirate.wallet.sdk.rpc.Service.Duration, pirate.wallet.sdk.rpc.Service.PingResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Ping"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.Duration.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pirate.wallet.sdk.rpc.Service.PingResponse.getDefaultInstance()))
              .setSchemaDescriptor(new CompactTxStreamerMethodDescriptorSupplier("Ping"))
              .build();
        }
      }
    }
    return getPingMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static CompactTxStreamerStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<CompactTxStreamerStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<CompactTxStreamerStub>() {
        @java.lang.Override
        public CompactTxStreamerStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new CompactTxStreamerStub(channel, callOptions);
        }
      };
    return CompactTxStreamerStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static CompactTxStreamerBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<CompactTxStreamerBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<CompactTxStreamerBlockingStub>() {
        @java.lang.Override
        public CompactTxStreamerBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new CompactTxStreamerBlockingStub(channel, callOptions);
        }
      };
    return CompactTxStreamerBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static CompactTxStreamerFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<CompactTxStreamerFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<CompactTxStreamerFutureStub>() {
        @java.lang.Override
        public CompactTxStreamerFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new CompactTxStreamerFutureStub(channel, callOptions);
        }
      };
    return CompactTxStreamerFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void getLiteWalletBlockGroup(pirate.wallet.sdk.rpc.Service.BlockID request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.BlockID> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetLiteWalletBlockGroupMethod(), responseObserver);
    }

    /**
     * <pre>
     * Return the height of the tip of the best chain
     * </pre>
     */
    default void getLatestBlock(pirate.wallet.sdk.rpc.Service.ChainSpec request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.BlockID> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetLatestBlockMethod(), responseObserver);
    }

    /**
     * <pre>
     * Return the compact block corresponding to the given block identifier
     * </pre>
     */
    default void getBlock(pirate.wallet.sdk.rpc.Service.BlockID request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.CompactFormats.CompactBlock> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetBlockMethod(), responseObserver);
    }

    /**
     * <pre>
     * Return a list of consecutive compact blocks
     * </pre>
     */
    default void getBlockRange(pirate.wallet.sdk.rpc.Service.BlockRange request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.CompactFormats.CompactBlock> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetBlockRangeMethod(), responseObserver);
    }

    /**
     * <pre>
     * Get the historical and current prices
     * </pre>
     */
    default void getARRRPrice(pirate.wallet.sdk.rpc.Service.PriceRequest request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.PriceResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetARRRPriceMethod(), responseObserver);
    }

    /**
     */
    default void getCurrentARRRPrice(pirate.wallet.sdk.rpc.Service.Empty request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.PriceResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetCurrentARRRPriceMethod(), responseObserver);
    }

    /**
     * <pre>
     * Return the requested full (not compact) transaction (as from zcashd)
     * </pre>
     */
    default void getTransaction(pirate.wallet.sdk.rpc.Service.TxFilter request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.RawTransaction> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTransactionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Submit the given transaction to the Zcash network
     * </pre>
     */
    default void sendTransaction(pirate.wallet.sdk.rpc.Service.RawTransaction request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.SendResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSendTransactionMethod(), responseObserver);
    }

    /**
     * <pre>
     * Return the txids corresponding to the given t-address within the given block range
     * </pre>
     */
    default void getTaddressTxids(pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.RawTransaction> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTaddressTxidsMethod(), responseObserver);
    }

    /**
     * <pre>
     * Legacy API that is used as a fallback for t-Address support, if the server is running the old version (lwdv2)
     * </pre>
     */
    default void getAddressTxids(pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.RawTransaction> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetAddressTxidsMethod(), responseObserver);
    }

    /**
     */
    default void getTaddressBalance(pirate.wallet.sdk.rpc.Service.AddressList request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.Balance> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTaddressBalanceMethod(), responseObserver);
    }

    /**
     */
    default io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.Address> getTaddressBalanceStream(
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.Balance> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getGetTaddressBalanceStreamMethod(), responseObserver);
    }

    /**
     * <pre>
     * Return the compact transactions currently in the mempool; the results
     * can be a few seconds out of date. If the Exclude list is empty, return
     * all transactions; otherwise return all *except* those in the Exclude list
     * (if any); this allows the client to avoid receiving transactions that it
     * already has (from an earlier call to this rpc). The transaction IDs in the
     * Exclude list can be shortened to any number of bytes to make the request
     * more bandwidth-efficient; if two or more transactions in the mempool
     * match a shortened txid, they are all sent (none is excluded). Transactions
     * in the exclude list that don't exist in the mempool are ignored.
     * </pre>
     */
    default void getMempoolTx(pirate.wallet.sdk.rpc.Service.Exclude request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.CompactFormats.CompactTx> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetMempoolTxMethod(), responseObserver);
    }

    /**
     */
    default void getMempoolStream(pirate.wallet.sdk.rpc.Service.Empty request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.RawTransaction> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetMempoolStreamMethod(), responseObserver);
    }

    /**
     * <pre>
     * GetTreeState returns the note commitment tree state corresponding to the given block.
     * See section 3.7 of the Zcash protocol specification. It returns several other useful
     * values also (even though they can be obtained using GetBlock).
     * The block can be specified by either height or hash.
     * </pre>
     */
    default void getTreeState(pirate.wallet.sdk.rpc.Service.BlockID request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.TreeState> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTreeStateMethod(), responseObserver);
    }

    /**
     */
    default void getAddressUtxos(pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.GetAddressUtxosReplyList> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetAddressUtxosMethod(), responseObserver);
    }

    /**
     */
    default void getAddressUtxosStream(pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.GetAddressUtxosReply> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetAddressUtxosStreamMethod(), responseObserver);
    }

    /**
     * <pre>
     * Return information about this lightwalletd instance and the blockchain
     * </pre>
     */
    default void getLightdInfo(pirate.wallet.sdk.rpc.Service.Empty request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.LightdInfo> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetLightdInfoMethod(), responseObserver);
    }

    /**
     * <pre>
     * Testing-only, requires lightwalletd --ping-very-insecure (do not enable in production)
     * </pre>
     */
    default void ping(pirate.wallet.sdk.rpc.Service.Duration request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.PingResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPingMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service CompactTxStreamer.
   */
  public static abstract class CompactTxStreamerImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return CompactTxStreamerGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service CompactTxStreamer.
   */
  public static final class CompactTxStreamerStub
      extends io.grpc.stub.AbstractAsyncStub<CompactTxStreamerStub> {
    private CompactTxStreamerStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CompactTxStreamerStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new CompactTxStreamerStub(channel, callOptions);
    }

    /**
     */
    public void getLiteWalletBlockGroup(pirate.wallet.sdk.rpc.Service.BlockID request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.BlockID> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetLiteWalletBlockGroupMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Return the height of the tip of the best chain
     * </pre>
     */
    public void getLatestBlock(pirate.wallet.sdk.rpc.Service.ChainSpec request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.BlockID> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetLatestBlockMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Return the compact block corresponding to the given block identifier
     * </pre>
     */
    public void getBlock(pirate.wallet.sdk.rpc.Service.BlockID request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.CompactFormats.CompactBlock> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetBlockMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Return a list of consecutive compact blocks
     * </pre>
     */
    public void getBlockRange(pirate.wallet.sdk.rpc.Service.BlockRange request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.CompactFormats.CompactBlock> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getGetBlockRangeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Get the historical and current prices
     * </pre>
     */
    public void getARRRPrice(pirate.wallet.sdk.rpc.Service.PriceRequest request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.PriceResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetARRRPriceMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getCurrentARRRPrice(pirate.wallet.sdk.rpc.Service.Empty request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.PriceResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetCurrentARRRPriceMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Return the requested full (not compact) transaction (as from zcashd)
     * </pre>
     */
    public void getTransaction(pirate.wallet.sdk.rpc.Service.TxFilter request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.RawTransaction> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTransactionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Submit the given transaction to the Zcash network
     * </pre>
     */
    public void sendTransaction(pirate.wallet.sdk.rpc.Service.RawTransaction request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.SendResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSendTransactionMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Return the txids corresponding to the given t-address within the given block range
     * </pre>
     */
    public void getTaddressTxids(pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.RawTransaction> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getGetTaddressTxidsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Legacy API that is used as a fallback for t-Address support, if the server is running the old version (lwdv2)
     * </pre>
     */
    public void getAddressTxids(pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.RawTransaction> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getGetAddressTxidsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getTaddressBalance(pirate.wallet.sdk.rpc.Service.AddressList request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.Balance> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTaddressBalanceMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.Address> getTaddressBalanceStream(
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.Balance> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncClientStreamingCall(
          getChannel().newCall(getGetTaddressBalanceStreamMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * Return the compact transactions currently in the mempool; the results
     * can be a few seconds out of date. If the Exclude list is empty, return
     * all transactions; otherwise return all *except* those in the Exclude list
     * (if any); this allows the client to avoid receiving transactions that it
     * already has (from an earlier call to this rpc). The transaction IDs in the
     * Exclude list can be shortened to any number of bytes to make the request
     * more bandwidth-efficient; if two or more transactions in the mempool
     * match a shortened txid, they are all sent (none is excluded). Transactions
     * in the exclude list that don't exist in the mempool are ignored.
     * </pre>
     */
    public void getMempoolTx(pirate.wallet.sdk.rpc.Service.Exclude request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.CompactFormats.CompactTx> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getGetMempoolTxMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getMempoolStream(pirate.wallet.sdk.rpc.Service.Empty request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.RawTransaction> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getGetMempoolStreamMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * GetTreeState returns the note commitment tree state corresponding to the given block.
     * See section 3.7 of the Zcash protocol specification. It returns several other useful
     * values also (even though they can be obtained using GetBlock).
     * The block can be specified by either height or hash.
     * </pre>
     */
    public void getTreeState(pirate.wallet.sdk.rpc.Service.BlockID request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.TreeState> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTreeStateMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getAddressUtxos(pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.GetAddressUtxosReplyList> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetAddressUtxosMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getAddressUtxosStream(pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.GetAddressUtxosReply> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getGetAddressUtxosStreamMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Return information about this lightwalletd instance and the blockchain
     * </pre>
     */
    public void getLightdInfo(pirate.wallet.sdk.rpc.Service.Empty request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.LightdInfo> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetLightdInfoMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Testing-only, requires lightwalletd --ping-very-insecure (do not enable in production)
     * </pre>
     */
    public void ping(pirate.wallet.sdk.rpc.Service.Duration request,
        io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.PingResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPingMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service CompactTxStreamer.
   */
  public static final class CompactTxStreamerBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<CompactTxStreamerBlockingStub> {
    private CompactTxStreamerBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CompactTxStreamerBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new CompactTxStreamerBlockingStub(channel, callOptions);
    }

    /**
     */
    public pirate.wallet.sdk.rpc.Service.BlockID getLiteWalletBlockGroup(pirate.wallet.sdk.rpc.Service.BlockID request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetLiteWalletBlockGroupMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Return the height of the tip of the best chain
     * </pre>
     */
    public pirate.wallet.sdk.rpc.Service.BlockID getLatestBlock(pirate.wallet.sdk.rpc.Service.ChainSpec request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetLatestBlockMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Return the compact block corresponding to the given block identifier
     * </pre>
     */
    public pirate.wallet.sdk.rpc.CompactFormats.CompactBlock getBlock(pirate.wallet.sdk.rpc.Service.BlockID request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetBlockMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Return a list of consecutive compact blocks
     * </pre>
     */
    public java.util.Iterator<pirate.wallet.sdk.rpc.CompactFormats.CompactBlock> getBlockRange(
        pirate.wallet.sdk.rpc.Service.BlockRange request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getGetBlockRangeMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Get the historical and current prices
     * </pre>
     */
    public pirate.wallet.sdk.rpc.Service.PriceResponse getARRRPrice(pirate.wallet.sdk.rpc.Service.PriceRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetARRRPriceMethod(), getCallOptions(), request);
    }

    /**
     */
    public pirate.wallet.sdk.rpc.Service.PriceResponse getCurrentARRRPrice(pirate.wallet.sdk.rpc.Service.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetCurrentARRRPriceMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Return the requested full (not compact) transaction (as from zcashd)
     * </pre>
     */
    public pirate.wallet.sdk.rpc.Service.RawTransaction getTransaction(pirate.wallet.sdk.rpc.Service.TxFilter request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTransactionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Submit the given transaction to the Zcash network
     * </pre>
     */
    public pirate.wallet.sdk.rpc.Service.SendResponse sendTransaction(pirate.wallet.sdk.rpc.Service.RawTransaction request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSendTransactionMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Return the txids corresponding to the given t-address within the given block range
     * </pre>
     */
    public java.util.Iterator<pirate.wallet.sdk.rpc.Service.RawTransaction> getTaddressTxids(
        pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getGetTaddressTxidsMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Legacy API that is used as a fallback for t-Address support, if the server is running the old version (lwdv2)
     * </pre>
     */
    public java.util.Iterator<pirate.wallet.sdk.rpc.Service.RawTransaction> getAddressTxids(
        pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getGetAddressTxidsMethod(), getCallOptions(), request);
    }

    /**
     */
    public pirate.wallet.sdk.rpc.Service.Balance getTaddressBalance(pirate.wallet.sdk.rpc.Service.AddressList request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTaddressBalanceMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Return the compact transactions currently in the mempool; the results
     * can be a few seconds out of date. If the Exclude list is empty, return
     * all transactions; otherwise return all *except* those in the Exclude list
     * (if any); this allows the client to avoid receiving transactions that it
     * already has (from an earlier call to this rpc). The transaction IDs in the
     * Exclude list can be shortened to any number of bytes to make the request
     * more bandwidth-efficient; if two or more transactions in the mempool
     * match a shortened txid, they are all sent (none is excluded). Transactions
     * in the exclude list that don't exist in the mempool are ignored.
     * </pre>
     */
    public java.util.Iterator<pirate.wallet.sdk.rpc.CompactFormats.CompactTx> getMempoolTx(
        pirate.wallet.sdk.rpc.Service.Exclude request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getGetMempoolTxMethod(), getCallOptions(), request);
    }

    /**
     */
    public java.util.Iterator<pirate.wallet.sdk.rpc.Service.RawTransaction> getMempoolStream(
        pirate.wallet.sdk.rpc.Service.Empty request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getGetMempoolStreamMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * GetTreeState returns the note commitment tree state corresponding to the given block.
     * See section 3.7 of the Zcash protocol specification. It returns several other useful
     * values also (even though they can be obtained using GetBlock).
     * The block can be specified by either height or hash.
     * </pre>
     */
    public pirate.wallet.sdk.rpc.Service.TreeState getTreeState(pirate.wallet.sdk.rpc.Service.BlockID request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTreeStateMethod(), getCallOptions(), request);
    }

    /**
     */
    public pirate.wallet.sdk.rpc.Service.GetAddressUtxosReplyList getAddressUtxos(pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetAddressUtxosMethod(), getCallOptions(), request);
    }

    /**
     */
    public java.util.Iterator<pirate.wallet.sdk.rpc.Service.GetAddressUtxosReply> getAddressUtxosStream(
        pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getGetAddressUtxosStreamMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Return information about this lightwalletd instance and the blockchain
     * </pre>
     */
    public pirate.wallet.sdk.rpc.Service.LightdInfo getLightdInfo(pirate.wallet.sdk.rpc.Service.Empty request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetLightdInfoMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Testing-only, requires lightwalletd --ping-very-insecure (do not enable in production)
     * </pre>
     */
    public pirate.wallet.sdk.rpc.Service.PingResponse ping(pirate.wallet.sdk.rpc.Service.Duration request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPingMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service CompactTxStreamer.
   */
  public static final class CompactTxStreamerFutureStub
      extends io.grpc.stub.AbstractFutureStub<CompactTxStreamerFutureStub> {
    private CompactTxStreamerFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CompactTxStreamerFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new CompactTxStreamerFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<pirate.wallet.sdk.rpc.Service.BlockID> getLiteWalletBlockGroup(
        pirate.wallet.sdk.rpc.Service.BlockID request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetLiteWalletBlockGroupMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Return the height of the tip of the best chain
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<pirate.wallet.sdk.rpc.Service.BlockID> getLatestBlock(
        pirate.wallet.sdk.rpc.Service.ChainSpec request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetLatestBlockMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Return the compact block corresponding to the given block identifier
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<pirate.wallet.sdk.rpc.CompactFormats.CompactBlock> getBlock(
        pirate.wallet.sdk.rpc.Service.BlockID request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetBlockMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Get the historical and current prices
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<pirate.wallet.sdk.rpc.Service.PriceResponse> getARRRPrice(
        pirate.wallet.sdk.rpc.Service.PriceRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetARRRPriceMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<pirate.wallet.sdk.rpc.Service.PriceResponse> getCurrentARRRPrice(
        pirate.wallet.sdk.rpc.Service.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetCurrentARRRPriceMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Return the requested full (not compact) transaction (as from zcashd)
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<pirate.wallet.sdk.rpc.Service.RawTransaction> getTransaction(
        pirate.wallet.sdk.rpc.Service.TxFilter request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTransactionMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Submit the given transaction to the Zcash network
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<pirate.wallet.sdk.rpc.Service.SendResponse> sendTransaction(
        pirate.wallet.sdk.rpc.Service.RawTransaction request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSendTransactionMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<pirate.wallet.sdk.rpc.Service.Balance> getTaddressBalance(
        pirate.wallet.sdk.rpc.Service.AddressList request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTaddressBalanceMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * GetTreeState returns the note commitment tree state corresponding to the given block.
     * See section 3.7 of the Zcash protocol specification. It returns several other useful
     * values also (even though they can be obtained using GetBlock).
     * The block can be specified by either height or hash.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<pirate.wallet.sdk.rpc.Service.TreeState> getTreeState(
        pirate.wallet.sdk.rpc.Service.BlockID request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTreeStateMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<pirate.wallet.sdk.rpc.Service.GetAddressUtxosReplyList> getAddressUtxos(
        pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetAddressUtxosMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Return information about this lightwalletd instance and the blockchain
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<pirate.wallet.sdk.rpc.Service.LightdInfo> getLightdInfo(
        pirate.wallet.sdk.rpc.Service.Empty request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetLightdInfoMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Testing-only, requires lightwalletd --ping-very-insecure (do not enable in production)
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<pirate.wallet.sdk.rpc.Service.PingResponse> ping(
        pirate.wallet.sdk.rpc.Service.Duration request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPingMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_LITE_WALLET_BLOCK_GROUP = 0;
  private static final int METHODID_GET_LATEST_BLOCK = 1;
  private static final int METHODID_GET_BLOCK = 2;
  private static final int METHODID_GET_BLOCK_RANGE = 3;
  private static final int METHODID_GET_ARRRPRICE = 4;
  private static final int METHODID_GET_CURRENT_ARRRPRICE = 5;
  private static final int METHODID_GET_TRANSACTION = 6;
  private static final int METHODID_SEND_TRANSACTION = 7;
  private static final int METHODID_GET_TADDRESS_TXIDS = 8;
  private static final int METHODID_GET_ADDRESS_TXIDS = 9;
  private static final int METHODID_GET_TADDRESS_BALANCE = 10;
  private static final int METHODID_GET_MEMPOOL_TX = 11;
  private static final int METHODID_GET_MEMPOOL_STREAM = 12;
  private static final int METHODID_GET_TREE_STATE = 13;
  private static final int METHODID_GET_ADDRESS_UTXOS = 14;
  private static final int METHODID_GET_ADDRESS_UTXOS_STREAM = 15;
  private static final int METHODID_GET_LIGHTD_INFO = 16;
  private static final int METHODID_PING = 17;
  private static final int METHODID_GET_TADDRESS_BALANCE_STREAM = 18;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_GET_LITE_WALLET_BLOCK_GROUP:
          serviceImpl.getLiteWalletBlockGroup((pirate.wallet.sdk.rpc.Service.BlockID) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.BlockID>) responseObserver);
          break;
        case METHODID_GET_LATEST_BLOCK:
          serviceImpl.getLatestBlock((pirate.wallet.sdk.rpc.Service.ChainSpec) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.BlockID>) responseObserver);
          break;
        case METHODID_GET_BLOCK:
          serviceImpl.getBlock((pirate.wallet.sdk.rpc.Service.BlockID) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.CompactFormats.CompactBlock>) responseObserver);
          break;
        case METHODID_GET_BLOCK_RANGE:
          serviceImpl.getBlockRange((pirate.wallet.sdk.rpc.Service.BlockRange) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.CompactFormats.CompactBlock>) responseObserver);
          break;
        case METHODID_GET_ARRRPRICE:
          serviceImpl.getARRRPrice((pirate.wallet.sdk.rpc.Service.PriceRequest) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.PriceResponse>) responseObserver);
          break;
        case METHODID_GET_CURRENT_ARRRPRICE:
          serviceImpl.getCurrentARRRPrice((pirate.wallet.sdk.rpc.Service.Empty) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.PriceResponse>) responseObserver);
          break;
        case METHODID_GET_TRANSACTION:
          serviceImpl.getTransaction((pirate.wallet.sdk.rpc.Service.TxFilter) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.RawTransaction>) responseObserver);
          break;
        case METHODID_SEND_TRANSACTION:
          serviceImpl.sendTransaction((pirate.wallet.sdk.rpc.Service.RawTransaction) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.SendResponse>) responseObserver);
          break;
        case METHODID_GET_TADDRESS_TXIDS:
          serviceImpl.getTaddressTxids((pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.RawTransaction>) responseObserver);
          break;
        case METHODID_GET_ADDRESS_TXIDS:
          serviceImpl.getAddressTxids((pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.RawTransaction>) responseObserver);
          break;
        case METHODID_GET_TADDRESS_BALANCE:
          serviceImpl.getTaddressBalance((pirate.wallet.sdk.rpc.Service.AddressList) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.Balance>) responseObserver);
          break;
        case METHODID_GET_MEMPOOL_TX:
          serviceImpl.getMempoolTx((pirate.wallet.sdk.rpc.Service.Exclude) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.CompactFormats.CompactTx>) responseObserver);
          break;
        case METHODID_GET_MEMPOOL_STREAM:
          serviceImpl.getMempoolStream((pirate.wallet.sdk.rpc.Service.Empty) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.RawTransaction>) responseObserver);
          break;
        case METHODID_GET_TREE_STATE:
          serviceImpl.getTreeState((pirate.wallet.sdk.rpc.Service.BlockID) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.TreeState>) responseObserver);
          break;
        case METHODID_GET_ADDRESS_UTXOS:
          serviceImpl.getAddressUtxos((pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.GetAddressUtxosReplyList>) responseObserver);
          break;
        case METHODID_GET_ADDRESS_UTXOS_STREAM:
          serviceImpl.getAddressUtxosStream((pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.GetAddressUtxosReply>) responseObserver);
          break;
        case METHODID_GET_LIGHTD_INFO:
          serviceImpl.getLightdInfo((pirate.wallet.sdk.rpc.Service.Empty) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.LightdInfo>) responseObserver);
          break;
        case METHODID_PING:
          serviceImpl.ping((pirate.wallet.sdk.rpc.Service.Duration) request,
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.PingResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_GET_TADDRESS_BALANCE_STREAM:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.getTaddressBalanceStream(
              (io.grpc.stub.StreamObserver<pirate.wallet.sdk.rpc.Service.Balance>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getGetLiteWalletBlockGroupMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.BlockID,
              pirate.wallet.sdk.rpc.Service.BlockID>(
                service, METHODID_GET_LITE_WALLET_BLOCK_GROUP)))
        .addMethod(
          getGetLatestBlockMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.ChainSpec,
              pirate.wallet.sdk.rpc.Service.BlockID>(
                service, METHODID_GET_LATEST_BLOCK)))
        .addMethod(
          getGetBlockMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.BlockID,
              pirate.wallet.sdk.rpc.CompactFormats.CompactBlock>(
                service, METHODID_GET_BLOCK)))
        .addMethod(
          getGetBlockRangeMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.BlockRange,
              pirate.wallet.sdk.rpc.CompactFormats.CompactBlock>(
                service, METHODID_GET_BLOCK_RANGE)))
        .addMethod(
          getGetARRRPriceMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.PriceRequest,
              pirate.wallet.sdk.rpc.Service.PriceResponse>(
                service, METHODID_GET_ARRRPRICE)))
        .addMethod(
          getGetCurrentARRRPriceMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.Empty,
              pirate.wallet.sdk.rpc.Service.PriceResponse>(
                service, METHODID_GET_CURRENT_ARRRPRICE)))
        .addMethod(
          getGetTransactionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.TxFilter,
              pirate.wallet.sdk.rpc.Service.RawTransaction>(
                service, METHODID_GET_TRANSACTION)))
        .addMethod(
          getSendTransactionMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.RawTransaction,
              pirate.wallet.sdk.rpc.Service.SendResponse>(
                service, METHODID_SEND_TRANSACTION)))
        .addMethod(
          getGetTaddressTxidsMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter,
              pirate.wallet.sdk.rpc.Service.RawTransaction>(
                service, METHODID_GET_TADDRESS_TXIDS)))
        .addMethod(
          getGetAddressTxidsMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.TransparentAddressBlockFilter,
              pirate.wallet.sdk.rpc.Service.RawTransaction>(
                service, METHODID_GET_ADDRESS_TXIDS)))
        .addMethod(
          getGetTaddressBalanceMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.AddressList,
              pirate.wallet.sdk.rpc.Service.Balance>(
                service, METHODID_GET_TADDRESS_BALANCE)))
        .addMethod(
          getGetTaddressBalanceStreamMethod(),
          io.grpc.stub.ServerCalls.asyncClientStreamingCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.Address,
              pirate.wallet.sdk.rpc.Service.Balance>(
                service, METHODID_GET_TADDRESS_BALANCE_STREAM)))
        .addMethod(
          getGetMempoolTxMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.Exclude,
              pirate.wallet.sdk.rpc.CompactFormats.CompactTx>(
                service, METHODID_GET_MEMPOOL_TX)))
        .addMethod(
          getGetMempoolStreamMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.Empty,
              pirate.wallet.sdk.rpc.Service.RawTransaction>(
                service, METHODID_GET_MEMPOOL_STREAM)))
        .addMethod(
          getGetTreeStateMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.BlockID,
              pirate.wallet.sdk.rpc.Service.TreeState>(
                service, METHODID_GET_TREE_STATE)))
        .addMethod(
          getGetAddressUtxosMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg,
              pirate.wallet.sdk.rpc.Service.GetAddressUtxosReplyList>(
                service, METHODID_GET_ADDRESS_UTXOS)))
        .addMethod(
          getGetAddressUtxosStreamMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.GetAddressUtxosArg,
              pirate.wallet.sdk.rpc.Service.GetAddressUtxosReply>(
                service, METHODID_GET_ADDRESS_UTXOS_STREAM)))
        .addMethod(
          getGetLightdInfoMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.Empty,
              pirate.wallet.sdk.rpc.Service.LightdInfo>(
                service, METHODID_GET_LIGHTD_INFO)))
        .addMethod(
          getPingMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              pirate.wallet.sdk.rpc.Service.Duration,
              pirate.wallet.sdk.rpc.Service.PingResponse>(
                service, METHODID_PING)))
        .build();
  }

  private static abstract class CompactTxStreamerBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    CompactTxStreamerBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return pirate.wallet.sdk.rpc.Service.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("CompactTxStreamer");
    }
  }

  private static final class CompactTxStreamerFileDescriptorSupplier
      extends CompactTxStreamerBaseDescriptorSupplier {
    CompactTxStreamerFileDescriptorSupplier() {}
  }

  private static final class CompactTxStreamerMethodDescriptorSupplier
      extends CompactTxStreamerBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    CompactTxStreamerMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (CompactTxStreamerGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new CompactTxStreamerFileDescriptorSupplier())
              .addMethod(getGetLiteWalletBlockGroupMethod())
              .addMethod(getGetLatestBlockMethod())
              .addMethod(getGetBlockMethod())
              .addMethod(getGetBlockRangeMethod())
              .addMethod(getGetARRRPriceMethod())
              .addMethod(getGetCurrentARRRPriceMethod())
              .addMethod(getGetTransactionMethod())
              .addMethod(getSendTransactionMethod())
              .addMethod(getGetTaddressTxidsMethod())
              .addMethod(getGetAddressTxidsMethod())
              .addMethod(getGetTaddressBalanceMethod())
              .addMethod(getGetTaddressBalanceStreamMethod())
              .addMethod(getGetMempoolTxMethod())
              .addMethod(getGetMempoolStreamMethod())
              .addMethod(getGetTreeStateMethod())
              .addMethod(getGetAddressUtxosMethod())
              .addMethod(getGetAddressUtxosStreamMethod())
              .addMethod(getGetLightdInfoMethod())
              .addMethod(getPingMethod())
              .build();
        }
      }
    }
    return result;
  }
}
