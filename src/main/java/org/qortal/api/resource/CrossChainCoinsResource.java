package org.qortal.api.resource;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.bitcoinj.core.Context;
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.wallet.Wallet;
import org.qortal.api.ApiError;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.Security;
import org.qortal.api.model.crosschain.ForeignWalletRequest;
import org.qortal.api.model.crosschain.LocalWalletResponse;
import org.qortal.crosschain.*;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Authenticated, read-only registry API. No transfer or dynamic registration routes. */
@Path("/crosschain/coins")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Cross-Chain (Bitcoin clones)")
@SecurityRequirement(name = "apiKey")
public class CrossChainCoinsResource {
    @javax.ws.rs.core.Context HttpServletRequest request;

    @GET
    public LocalWalletResponse coins() {
        Security.checkApiCallAllowed(request);
        return new LocalWalletResponse(BitcoinCloneSupport.getRegistry().getAll().stream()
                .map(CrossChainCoinsResource::info).collect(Collectors.toList()));
    }

    @GET @Path("/{coin}")
    public LocalWalletResponse coin(@PathParam("coin") String id, @QueryParam("network") String network) {
        Security.checkApiCallAllowed(request);
        return new LocalWalletResponse(info(definition(id, network)));
    }

    @GET @Path("/{coin}/fees")
    public LocalWalletResponse fees(@PathParam("coin") String id, @QueryParam("network") String network) {
        Security.checkApiCallAllowed(request);
        BitcoinCloneDefinition definition = definition(id, network);
        BitcoinClone backend = backend(definition);
        try {
            return new LocalWalletResponse(Map.of("feePerKb", Long.toString(backend.getFeePerKb().value),
                    "p2shFee", Long.toString(backend.getP2shFee(null)),
                    "feeRequired", Long.toString(backend.getFeeRequired()), "source", "configured-fixed-policy"));
        } catch (ForeignBlockchainException e) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
        }
    }

    @GET @Path("/{coin}/height")
    public LocalWalletResponse height(@PathParam("coin") String id, @QueryParam("network") String network) {
        Security.checkApiCallAllowed(request);
        BitcoinClone backend = backend(definition(id, network));
        try {
            return new LocalWalletResponse(Map.of("height", backend.getBlockchainHeight(), "verification", "ELECTRUM_SERVER_ASSERTED"));
        } catch (ForeignBlockchainException e) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
        }
    }

    /** First external P2PKH address; does not claim that the address is unused. */
    @POST @Path("/{coin}/walletaddress")
    @Consumes(MediaType.APPLICATION_JSON)
    public LocalWalletResponse address(@PathParam("coin") String id, @QueryParam("network") String network,
            ForeignWalletRequest data) {
        Security.checkApiCallAllowed(request);
        BitcoinClone backend = backend(definition(id, network));
        try {
            if (data == null || data.xpub58 == null || data.xpub58.length() > 120
                    || data.rawTransactionHex != null || data.txId != null || data.expectedChainId != null)
                throw new IllegalArgumentException("Only an extended public key is accepted");
            Context.propagate(backend.getBitcoinjContext());
            DeterministicKey key = DeterministicKey.deserializeB58(data.xpub58, backend.getNetworkParameters());
            if (key.hasPrivKey()) throw new IllegalArgumentException("Private keys are not accepted");
            Wallet wallet = Wallet.fromWatchingKeyB58(backend.getNetworkParameters(), data.xpub58,
                    DeterministicHierarchy.BIP32_STANDARDISATION_TIME_SECS);
            return new LocalWalletResponse(Map.of("address", wallet.freshReceiveAddress().toString(),
                    "relativePath", "0/0", "format", "P2PKH"));
        } catch (IllegalArgumentException e) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA);
        }
    }

    @GET @Path("/{coin}/address/{address}/balance")
    public LocalWalletResponse balance(@PathParam("coin") String id, @QueryParam("network") String network,
            @PathParam("address") String address) {
        Security.checkApiCallAllowed(request);
        BitcoinCloneDefinition definition = definition(id, network);
        if (!definition.supports(BitcoinCloneDefinition.Capability.BALANCE)) throw invalid();
        BitcoinClone backend = backend(definition);
        if (address == null || address.length() > 100 || !backend.isValidAddress(address)) throw invalid();
        try {
            return new LocalWalletResponse(Map.of("confirmedAtomic", Long.toString(backend.getConfirmedBalance(address)),
                    "verification", "ELECTRUM_SERVER_ASSERTED"));
        } catch (ForeignBlockchainException e) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE);
        }
    }

    private BitcoinCloneDefinition definition(String id, String network) {
        try {
            BitcoinCloneDefinition definition = BitcoinCloneSupport.getRegistry().get(id, BitcoinCloneDefinition.Network.valueOf(network));
            if (definition == null) throw new IllegalArgumentException();
            return definition;
        } catch (IllegalArgumentException | NullPointerException e) { throw invalid(); }
    }

    private BitcoinClone backend(BitcoinCloneDefinition definition) {
        try { return BitcoinCloneSupport.getRegistry().backend(definition.id, definition.network); }
        catch (IllegalStateException e) { throw invalid(); }
    }

    private RuntimeException invalid() { return ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_CRITERIA); }

    static Map<String, Object> info(BitcoinCloneDefinition definition) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", definition.id);
        result.put("name", definition.name);
        result.put("symbol", definition.symbol);
        result.put("network", definition.network.name());
        result.put("genesisHash", definition.genesisHash);
        result.put("protocol", definition.protocol.name());
        result.put("derivation", definition.derivation.name());
        result.put("bip44CoinType", definition.bip44CoinType);
        result.put("capabilities", definition.capabilities.stream().map(Enum::name).collect(Collectors.toList()));
        result.put("enabled", BitcoinCloneSupport.getRegistry().getEnabled().contains(definition));
        result.put("builtIn", definition.isBuiltIn());
        return result;
    }
}
