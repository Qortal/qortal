package org.qortal.test.crosschain;

import pirate.wallet.sdk.rpc.CompactFormats.CompactBlock;
import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import org.junit.Ignore;
import org.junit.Test;
import org.qortal.controller.tradebot.TradeBot;
import org.qortal.crosschain.*;
import org.qortal.crypto.Crypto;
import org.qortal.transform.TransformationException;

import java.util.List;

import static org.junit.Assert.*;
import static org.qortal.crosschain.BitcoinyHTLC.Status.*;

public class PirateChainTests extends BitcoinyTests {

	@Override
	protected String getCoinName() {
		return "PirateChain";
	}

	@Override
	protected String getCoinSymbol() {
		return "ARRR";
	}

	@Override
	protected Bitcoiny getCoin() {
		return PirateChain.getInstance();
	}

	@Override
	protected void resetCoinForTesting() {
		Litecoin.resetForTesting();
	}

	@Override
	protected String getDeterministicKey58() {
		return null;
	}

	@Override
	protected String getDeterministicPublicKey58() {
		return null;
	}

	@Override
	protected String getRecipient() {
		return null;
	}

	public void makeGetMedianBlockTimeAssertions(long firstPeriod, long secondPeriod) {
		assertTrue("1st call should take less than 5 seconds", firstPeriod < 5000L);
		assertTrue("2nd call should take less than 5 seconds", secondPeriod < 5000L);
	}

	@Test
	public void testGetCompactBlocks() throws ForeignBlockchainException {
		int startHeight = 1000000;
		int count = 20;

		long before = System.currentTimeMillis();
		List<CompactBlock> compactBlocks = ((PirateChain) bitcoiny).getCompactBlocks(startHeight, count);
		long after = System.currentTimeMillis();

		System.out.println(String.format("Retrieval took: %d ms", after - before));

		for (CompactBlock block : compactBlocks) {
			System.out
					.println(String.format("Block height: %d, transaction count: %d", block.getHeight(), block.getVtxCount()));
		}

		assertEquals(count, compactBlocks.size());
	}

	@Test
	public void testGetRawTransaction() throws ForeignBlockchainException {
		String txHashLE = "fea4b0c1abcf8f0f3ddc2fa2f9438501ee102aad62a9ff18a5ce7d08774755c0";
		byte[] txBytes = HashCode.fromString(txHashLE).asBytes();
		// Pirate protocol expects txids in big-endian form, but block explorers use
		// txids in little-endian form
		Bytes.reverse(txBytes);
		String txHashBE = HashCode.fromBytes(txBytes).toString();

		byte[] rawTransaction = bitcoiny.getBlockchainProvider().getRawTransaction(txHashBE);
		assertNotNull(rawTransaction);
	}

	@Test
	public void testDeriveP2SHAddressWithT3Prefix() {
		byte[] creatorTradePrivateKey = TradeBot.generateTradePrivateKey();
		byte[] creatorTradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(creatorTradePrivateKey);
		byte[] tradePrivateKey = TradeBot.generateTradePrivateKey();
		byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] secretA = TradeBot.generateSecret();
		byte[] hashOfSecretA = Crypto.hash160(secretA);
		int lockTime = 1653233550;

		byte[] redeemScriptBytes = PirateChainHTLC.buildScript(tradeForeignPublicKey, lockTime,
				creatorTradeForeignPublicKey, hashOfSecretA);
		String p2shAddress = PirateChain.getInstance().deriveP2shAddress(redeemScriptBytes);
		assertTrue(p2shAddress.startsWith("t3"));
	}

	@Test
	public void testDeriveP2SHAddressWithBPrefix() {
		byte[] creatorTradePrivateKey = TradeBot.generateTradePrivateKey();
		byte[] creatorTradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(creatorTradePrivateKey);
		byte[] tradePrivateKey = TradeBot.generateTradePrivateKey();
		byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] secretA = TradeBot.generateSecret();
		byte[] hashOfSecretA = Crypto.hash160(secretA);
		int lockTime = 1653233550;

		byte[] redeemScriptBytes = PirateChainHTLC.buildScript(tradeForeignPublicKey, lockTime,
				creatorTradeForeignPublicKey, hashOfSecretA);
		String p2shAddress = PirateChain.getInstance().deriveP2shAddressBPrefix(redeemScriptBytes);
		assertTrue(p2shAddress.startsWith("b"));
	}

	@Test
	public void testHTLCStatusFunded() throws ForeignBlockchainException {
		String p2shAddress = "ba6Q5HWrWtmfU2WZqQbrFdRYsafA45cUAt";
		long p2shFee = 10000;
		final long minimumAmount = 10000 + p2shFee;
		BitcoinyHTLC.Status htlcStatus = PirateChainHTLC.determineHtlcStatus(bitcoiny.getBlockchainProvider(), p2shAddress,
				minimumAmount);
		assertEquals(FUNDED, htlcStatus);
	}

	@Test
	public void testHTLCStatusRedeemed() throws ForeignBlockchainException {
		String p2shAddress = "bYZrzSSgGp8aEGvihukoMGU8sXYrx19Wka";
		long p2shFee = 10000;
		final long minimumAmount = 10000 + p2shFee;
		BitcoinyHTLC.Status htlcStatus = PirateChainHTLC.determineHtlcStatus(bitcoiny.getBlockchainProvider(), p2shAddress,
				minimumAmount);
		assertEquals(REDEEMED, htlcStatus);
	}

	@Test
	public void testHTLCStatusRefunded() throws ForeignBlockchainException {
		String p2shAddress = "bE49izfVxz8odhu8c2BcUaVFUnt7NLFRgv";
		long p2shFee = 10000;
		final long minimumAmount = 10000 + p2shFee;
		BitcoinyHTLC.Status htlcStatus = PirateChainHTLC.determineHtlcStatus(bitcoiny.getBlockchainProvider(), p2shAddress,
				minimumAmount);
		assertEquals(REFUNDED, htlcStatus);
	}

	@Test
	public void testGetTxidForUnspentAddress() throws ForeignBlockchainException {
		String p2shAddress = "ba6Q5HWrWtmfU2WZqQbrFdRYsafA45cUAt";
		String txid = PirateChainHTLC.getFundingTxid(bitcoiny.getBlockchainProvider(), p2shAddress);

		// Reverse the byte order of the txid used by block explorers, to get to
		// big-endian form
		byte[] expectedTxidLE = HashCode.fromString("fea4b0c1abcf8f0f3ddc2fa2f9438501ee102aad62a9ff18a5ce7d08774755c0")
				.asBytes();
		Bytes.reverse(expectedTxidLE);
		String expectedTxidBE = HashCode.fromBytes(expectedTxidLE).toString();

		assertEquals(expectedTxidBE, txid);
	}

	@Test
	public void testGetTxidForUnspentAddressWithMinimumAmount() throws ForeignBlockchainException {
		String p2shAddress = "ba6Q5HWrWtmfU2WZqQbrFdRYsafA45cUAt";
		long p2shFee = 10000;
		final long minimumAmount = 10000 + p2shFee;
		String txid = PirateChainHTLC.getUnspentFundingTxid(bitcoiny.getBlockchainProvider(), p2shAddress, minimumAmount);

		// Reverse the byte order of the txid used by block explorers, to get to
		// big-endian form
		byte[] expectedTxidLE = HashCode.fromString("fea4b0c1abcf8f0f3ddc2fa2f9438501ee102aad62a9ff18a5ce7d08774755c0")
				.asBytes();
		Bytes.reverse(expectedTxidLE);
		String expectedTxidBE = HashCode.fromBytes(expectedTxidLE).toString();

		assertEquals(expectedTxidBE, txid);
	}

	@Test
	public void testGetTxidForSpentAddress() throws ForeignBlockchainException {
		String p2shAddress = "bE49izfVxz8odhu8c2BcUaVFUnt7NLFRgv"; // "t3KtVxeEb8srJofo6atMEpMpEP6TjEi8VqA";
		String txid = PirateChainHTLC.getFundingTxid(bitcoiny.getBlockchainProvider(), p2shAddress);

		// Reverse the byte order of the txid used by block explorers, to get to
		// big-endian form
		byte[] expectedTxidLE = HashCode.fromString("fb386fc8eea0fbf3ea37047726b92c39441652b32d8d62a274331687f7a1eca8")
				.asBytes();
		Bytes.reverse(expectedTxidLE);
		String expectedTxidBE = HashCode.fromBytes(expectedTxidLE).toString();

		assertEquals(expectedTxidBE, txid);
	}

	@Test
	public void testGetTransactionsForAddress() throws ForeignBlockchainException {
		String p2shAddress = "bE49izfVxz8odhu8c2BcUaVFUnt7NLFRgv"; // "t3KtVxeEb8srJofo6atMEpMpEP6TjEi8VqA";
		List<BitcoinyTransaction> transactions = bitcoiny.getBlockchainProvider()
				.getAddressBitcoinyTransactions(p2shAddress, false);

		assertEquals(2, transactions.size());
	}

	@Test
	public void testDecodeRawP2SHTransaction() throws TransformationException {
		String transactionDataHex = "0400008085202f890002204e00000000000017a9140e8a360d8a54e3d684b7b43c293c6b26ca594abf8700000000000000006e6a4c6b630400738762b17521029ddc2860644ef2a3b6c7310432e2f4231f21171e26f04150363340cd17565ac8ac6782012088a914ab08c6bf2771e0b287303cc14cc02a6bd41a1fad8821029ddc2860644ef2a3b6c7310432e2f4231f21171e26f04150363340cd17565ac8ac6800000000b21e1d0030750000000000000136f025c5ec654eac6aae884c46162aceb7bc3dfd8a800de7adc01c7aef4c85f158ba30e0c4ed45509db91d76f60f6a35f0fd1fcbbac3019d83daa90bc1e05a28b13dcfc12225817a634b86594447e179915dd57accaae2a034d5c66171300e5a91fd576d08f60577a5518a66363349ef2c33771702aa91dfc15ca6bf32506899a2e90488edd6d7c5199476438f10b5c88d49768a5433930a1793aad1303ce0db48af74e0e7edc4104ced5214a94ee0d580fd3b6efbcab863a05c35d320b987fa58968e7f49f6d103dc5a45635d3ac2243eca854256f9ed856d352951402f9b860133ad082e2a1e3330d9923d216917ace5a5bcc350666be0fee214b667faef3df5e23e21d9cabaa90004a11446c20cc0a4756c7712b7b51d3906de8a6ba09114c26aa97ee5f833932a40dcadb830d216c84ded926738b57aaf33a34e0081e5526d8608db1a7008ead9387a3ae9ad4825b2068563363fdaacb860b2cab9e551407b35aa33a46960617df24ab075b9a399f721daa4b16926e44968c3fc15267704024db9f6ca6a09a3f46739f478190da73184e405866a31ee41dec2d633160eedabf9d5d7f361407fb3346ea4a26060b72ca70924745730e8821a22699fb96cff54f0a4270cadfb81193df48e0f573247ebd44b2e10fcd67e3877a4faf028324006d12dbb40b690351ca5ee2514ab5fdbabc21fd9ed9c03f98d45a97f10718d680bd83620187fd9b7093e406cbace69f11a729af72edcbf10eebd8bba7148be19380f6db7a246b9c1fcd238282d050d2000430544893843e5ab2e10d65156e7810bf4eb6401889ab55d7c8ae0cf901239f0e8532a47f161b1771895480173ef64202136d6c04635b78878e12782eabe20dd781f50138a2d7cc55d73070299b8fa1ae9a6a388b9b5c9b01b86feb20d14e7c48b03ff041eac240b2918b0ad1c5fe2f8c03591f6aa0860c87f9d4abd70b842d522629d8fdb7436ea4e2de7b4a2b119ed3d042ed75ad22f2ae6d16abeb3b7a2a7479acf8788184e1058aab5dee0b4ac105f33c57a5a0ba5744ef5de6a65a0ee99988da509c1d0f1e939204619276d29d5f5eecf2850c206dc112e34f06b37341cde63577dfb93c75623f102dd8b3413c31bf38ba04f438df22de81cf42720d6265de2593b6938a82c949c295c546d2343e37104220be8d32172706e205e246e787800357ddc4a10a1b3ec98ae38f6886e40ef2c8e5c3841f60a80d88263e286f66567892d49e63298024d02ce47926a6292cfc03ad03d559cc13a0b1562ea28f9fa1d6496cec47e6743deed4f266bc23b32a3196ba71e29cdce85833129b0777bbdbfd22c45d208ff71d79ca267d6b8556130677147a18b58f72d6628c20bdc87933ae1ec1a7c3a7b1efceb87739b99b785de32bb65e37014e84f905f1d288b927607759184f05cd41ace1f154397eaa4436710328dffcc95dc16399c449e3aabfd87ee160b971def1464c11cdd49609b8c6bf7892badd5483705fcfdfbe0b0345078a5c869245b7dddebef35853dbbefa3527e74d31d9db453251fc0e2b1ef3209e783a696b90ca915bfbaef58161ae8496d16f93d1f05ca9bbf1d2278bddaedf29c2b9e98bcfa5526d229896ed1b50dc71a607805f207eae23fba8a190f57dcbdf1db50e923828970fff049c10f6510e88a9c2fb9e0d1846fadf8ff8c739834cf7570cad2a84a6be4cd78c2344ef1f8745d41517fb825617ecdf0cea098b9f9ed4ba70710e55a0cff793d05b77bbf63f26bc4bec8eabd482c19f1ba6b79a11302763228a3af8bd309b7bad297adccfe6b9033bca82474058ecf2560ff4bbb49f5ea22cb2aa58997013f1c363c9d089264a0e3adbcf1b5a2f8e3440b43f3272e0ada44f4a1f43e549fe6c105752c3eb13b496dbbe65e761e0b0552a004b7854388778449be9726f00e600dcdabfd4aedfb93cb4280c338f23e020903ce1215e4b4158527bc427c8179ec20198a4c0728142a5fca870514a15bf07f9d4eb40e1ca4e67719b23eac04c57276cc7808ac060cb3966940ec4323a0cd3700a1b1189bd31d39e2fe47356781df65547c11e221aca1b80c16304fc33fa070b9b0a5032b967ce4b70257d1d7ff0d6085e0aa1b3f87ceda747c765f095624237ece471bd7c70f5e2e7f8db38036bc6a2a0d7562e0cdd03f05fb7828eb82511c5c843c2577e97151724c1ce5f365fe1cf57ec8ad09a71b5a6732be7bb1b374c26b870b3a2d2b61d889e5d7f2aab13f54774e183950ac7d578b336a658a567056fb26e305875cf4928a9a35e27391f0287f79cc2b6ec77ddc5363001d03ca835ff5aca983805990b233c1d1289fb06083c4c922052c3e4b5575aef9724bcaad5d60a73a7ef00063b0fafa1a5ebdcf745717e30d9293c9b450b70a3c83b902f3d4085e021f0b06ddfcdc2f4258c5f09439dca31f0c54f4998c36e1c53e2cfacdafad4a6a09f8c87ed07c0fd28ec9a83daf222562b3bcab982a29a9480da9166060cf2e8ac69df5def38c8dc6a6638999a559004926402ec597fd3d918a844c6d1ae3e949aafad667a224cbf3441a988f6e0478e513b60b3d51031f1fa4b08d2124e18f585c31429180f18b67b5dd8d33f82caa3e269f3ef7934e9d9192608edda8cc2fe40fd9458a9e832bd6a39e4a5cb0d14fc9f1fda53e127cc1246ab416321c61068c0868ad00cd84577f0347f6b03429b6f7d51982d2b818cdbbb30e89d0d43a2e84f74ec8780e5dd42f8dec0ff80fa56b889604d6617a59b89c50321adf5d8db7ad7bfc1329d8f0aba0408951142619a41ba74a905956407193b666a2e9bd90a5135210274971f4f036453db9218ac5b9e5417a5335abfdc8ed466538a4ac8df4fca5da5f92dbc1833f1b585194119eeb5487925d8ac89eea529ad72550072de6e43efc983c50043d6c1f677c83bcde80d265dd0efe7c12d0bb95178c0a45c7dc55b2ce6d0ef9a897b31b5d18a0dfe0db982c503b58950b471bf4003109f661844aa4197f11cd2d9f1153f5b6bd32b87919c4069ee810eb1bdb70f95f6be42d39915451da63a85979ed6836614874cd72ebfc61df8f61181482531405c6dfde5be00d7ba0c30c751ca511498b6cc14f427d3fdd40f5550236786f933e2913a4bb6e0620efcc191f1d434c2eaeb29a52775b41a32e2344bcf0912eaf674d2c83b8eea225794e4ed88fc761e0ecbd9a2ca747de1415f7bb254c11482500e0603cc470f2d1b09e462247846d714a12c55f9defa18f361576e7d4f39d69d56d786c8297ec8eb9421022aa057cfb02d5cd4a1abd0257f4aa425e148536df05";
		BitcoinyTransaction bitcoinyTransaction = PirateChain.deserializeRawTransaction(transactionDataHex);
		assertEquals(0, bitcoinyTransaction.inputs.size());
		assertEquals(2, bitcoinyTransaction.outputs.size());
		assertEquals("a9140e8a360d8a54e3d684b7b43c293c6b26ca594abf87", bitcoinyTransaction.outputs.get(0).scriptPubKey);
		assertEquals(
				"6a4c6b630400738762b17521029ddc2860644ef2a3b6c7310432e2f4231f21171e26f04150363340cd17565ac8ac6782012088a914ab08c6bf2771e0b287303cc14cc02a6bd41a1fad8821029ddc2860644ef2a3b6c7310432e2f4231f21171e26f04150363340cd17565ac8ac68",
				bitcoinyTransaction.outputs.get(1).scriptPubKey);
		assertEquals(0, bitcoinyTransaction.locktime);
	}

	@Test
	public void testDecodeRawRedeemTransaction() throws TransformationException {
		String transactionDataHex = "0400008085202f8901efb7c5be4e870464795737109ed02b6c9b5e60e8676f68245c390b4cab09917e00000000d847304402203929739d51dbc4b4d3435cf8b4930101601f670b3e8bcb23fde122b870385fba0220195aed5ed09b7dd342906c67c8da2896d00049bc75127c96724d03b7981fdbd80120baa6e68ffb2ddf19df6fce1b37d7e38b98f3706d5709e46d4f45f71c2e4bb7c301004c6b630436798662b17521037f7e5ab23099885d373da9b9af701cffe06e62225f96f74be3ad6b5aeaad5b82ac6782012088a91429096c1c7a55cc968a98032fbff1cf5f3e98b1c68821037f7e5ab23099885d373da9b9af701cffe06e62225f96f74be3ad6b5aeaad5b82ac68ffffffff0000000000a91e1d00f0d8ffffffffffff000116b2c57a7bcb22e6240ddfd5764ba12bbe589748c8cab6d34d40b2699457a3cbf7bdf6ada43d7649508ecc22f6f6d54d693b6b7434138f8aced6386be251803cd1fb65e7eb2a876ee1d41d74939d398dcb9152e755587325d51ff94b548baf4cff7db34f02e1e419294d8b5ddd55996179da0096751585213b87211626cbf4e4fde3c62a7b40310b375101cbd15983b2b9034fa29293cd1733ee36ade06027005f3e41f93394f15918663b82165aad714fa5851b41f981c77dfdeecdb779472ca30979ec888d33c30fae0d702a2b5fb8a349b5c466338ecc8eef63cb76d04b2ff6ef3b83029f06f875ce0f825fad3538fa5aa3b9db54eaa4f81533ba68af227552707e82e8ad8331b8bb4ba3624a087c4b2ddbe08f21771a0eab22491e444ad663afbe53ec25d2bd9e75295e78b4e9642e65ba04ea4f307a7d3062c4ccf85a71251147abcdd28f7c900b5e8ff19a7d9e24846e9477e277db46f8510a42f8e4a966783818ee8c9fcfa4dc2b1cc7c97b69f8bbd32b5249411b3f0308626e7af28e44a06031e673340c092ec0af3c3b9250c7f00da03ba9634fc0b1a05833330cc1c1a1915ead0a81d62ec420b83936aeff8835100060b2d7e22f9bdb4c679ec38bc3946d66d139c721093902031b0657524736316927588f70817bd117bd757cb64f5cea95d21a5c629e6e784e5f1ea7afce150a4c2e07ab35cca2d8d242de12fc953f2642ffb13fd3e03e25bb7a3993d853263c4a565694554452ae28bf8cb1d83793c3fc6a78a7302b0941c233ba9da1febd5185527727ab3b53aff475408e41924b490f99e532cb24295e1053160077c41fb892695ced2cc73d6bbb18bbd4d0b4d48a3d2c3500c5bb472f404617d00704d0407e51b52284d82e03b901f3acaf99990645ac2ee3a895cc3521126385e3046152666c132c902ba34db629c076396dd6aab718b97bd453d6b7dd3c080528fef4e0482413d448b7f83d1966e10f0fe0fcc5315498abceb84a9e3a6b7ca2e3d0694a92a71c4f1b32fb950acaecbaa72aec501f0dfecb87684b40bbb5abdbc0ae21db978c9112424201c8b711c776c008b87753e9f512be610d594fde628b17bbdbc7dd0c06b479c991d8d7826231f6f0d13529b0ac4f9ae4203fe7fda84c290b120f38e0c0b0c427846a6c3beb7216100a049ba9f014b4fcd88a5a08866ca1e1f3f09f118a77a280486d50241260829de93e8fcd039e0a274767d4388b900e4a2ce4b592f0e416997d7660df0555dee05ddbd484d9f1512bffb683f2d01966b123140bf72c5631e81b73a1f857547ed736c3b2a1f1ac12eddb8d7c9855b523988b72ed0052789154fe244eec1166e557c546f590c0ecc6d8a6988f1f4bb09ba2347cafd2dc1c779c08bb132ddd56d54a339ef6b9dc0f0217fbeed4dcb721394290051e06";
		BitcoinyTransaction bitcoinyTransaction = PirateChain.deserializeRawTransaction(transactionDataHex);
		assertEquals(1, bitcoinyTransaction.inputs.size());
		assertEquals(0, bitcoinyTransaction.outputs.size());
		assertEquals(
				"47304402203929739d51dbc4b4d3435cf8b4930101601f670b3e8bcb23fde122b870385fba0220195aed5ed09b7dd342906c67c8da2896d00049bc75127c96724d03b7981fdbd80120baa6e68ffb2ddf19df6fce1b37d7e38b98f3706d5709e46d4f45f71c2e4bb7c301004c6b630436798662b17521037f7e5ab23099885d373da9b9af701cffe06e62225f96f74be3ad6b5aeaad5b82ac6782012088a91429096c1c7a55cc968a98032fbff1cf5f3e98b1c68821037f7e5ab23099885d373da9b9af701cffe06e62225f96f74be3ad6b5aeaad5b82ac68",
				bitcoinyTransaction.inputs.get(0).scriptSig);
		assertEquals("efb7c5be4e870464795737109ed02b6c9b5e60e8676f68245c390b4cab09917e",
				bitcoinyTransaction.inputs.get(0).outputTxHash);
		assertEquals(0, bitcoinyTransaction.inputs.get(0).outputVout);
		assertEquals(-1, bitcoinyTransaction.inputs.get(0).sequence);
		assertEquals(0, bitcoinyTransaction.locktime);
	}

	@Test
	public void testDecodeRawRefundTransaction() throws TransformationException {
		String transactionDataHex = "0400008085202f8901a8eca1f787163374a2628d2db3521644392cb926770437eaf3fba0eec86f38fb00000000b8483045022100eb217ddf7c671f4d9d74f02b5d5a4340ec69e7e433d6fb839dc1aa0bf8a5059b02206b45f78df09a75ad151bb4bc91e5d555098ceace53cda938bb52ff0ad34dd4a40101514c6b630400738762b17521029ddc2860644ef2a3b6c7310432e2f4231f21171e26f04150363340cd17565ac8ac6782012088a914ab08c6bf2771e0b287303cc14cc02a6bd41a1fad8821029ddc2860644ef2a3b6c7310432e2f4231f21171e26f04150363340cd17565ac8ac68feffffff0000738762111f1d00f0d8ffffffffffff00012a22d50c89ad18ec56b09efb1ce5044c7ad9abcd378f935189168b96210b1e407ede37181c3b51841a78e786a3ba1ef87baac7d7408205a13ec95447e3409c5819f6bbfd31a80ba5d41d367410c5b106788b9e75c49c2916a755b324a10e9210579f97aa8267a490e5e80210e123e657007bf0810294bcc8d73f7cf1b8a1acf2d87edf5dfff0e61423a48fffac84c648bfebec3ff0fa97009ff3d945c6e895b7f5a1e09d50a7920b776b28f5e97dac3eeed25ec0f1ad3cd3ac9e0517fe3c03990fa5303e75b60522e8ffc3ef3493d4460fa14cd8019a96416519408806ee2afa891f1335b6dfd4d1590e9f7939b0718af4758f832d836b928bdfc9329741d906e935316e3d4ec3a24eaf4fc06c321b78bd524d09b4fe09242dd7280ff3f841b56375a6cffd9661db089d1ee6ba4f6e767da194bd4fcd21e860bfa84dd7f431381795ff5998c66b594cf4bfb78c80d044f02e26d9ec0b35e5968ffb98c9be2b196a8a9238116cbd343b3718025c43ae65bacbbc400fd65f957aee17ffb7322aa1dca016a30a5bf21bac910a509eecd54274113c0f24a34786dda9b364e71d1ae0872db8ba06c864cf02bffec15c62ac240de0507230f88679cb7b7c5555c08277840679f89a8ebc2ccc37cdaee5486857293eb2a227191193e03d073ad6a6293a400dabf1ef90215585b78efb6f970490a07382072a0000666b6b70cde068e8841ee4b695088f7a136b44947d03f6961e88d88f79e5e6d7e0b96b61b3d4cc07cd4666fc1e5385915083e0af96ca937320babe0ba43d7914ee501a021fcb6ac02a2232f976a9b123f54ceb1524fe718aa143c1f509bc297419b6cb5277cf16e105a857fd38ce33bc437b52628a8c5eb0f6a4022901f9efddd26c325aa2ad393dd3ec11a75be2884927a322207e7ff8bacb5cdf78503e2051ddac6d2a990471bb9b95e0e865b491d17ec9ee245f8800476f9c4d2475f0ee01561f29edd64dff3d0af2c5847dfcbd498aae3a4c6d497ff9cb359b326599928643567634ef68d3483d81ba364c47e2418c94aa6b70a8cf451f67ba78a194de9e297e1c9be62f2d580e1c3ecd301a3875fcd63fdbf8bb17d0d815c893590fabd76b0bd0f96ab2ff4e43aee4df2f8b07e8cd7342632b9c263dd57df083b96a95276f7576005f840b0d3d95f55f82425381ebaa6cdd64d943c1387695be4206ac749621514d30e460d0260f32c9493d3c35ab99ab5e166fc2b187600996406f96eb0d6c634a683138fcfa31fd028db05233d09c0cdd02a7737e70ebc2c5c5a42c0e9c7ab030020623e8c9d4807dbf8dde6224ca2f75c09aa08d3147d9761c00fa7012218ef325383d64ad35a61e37dc34aae5de1a233ae607c11094508eb39f4813025c4bd121492842b69182c288ec481d4e68ed9ce160e2b4f682e2ff6806";
		BitcoinyTransaction bitcoinyTransaction = PirateChain.deserializeRawTransaction(transactionDataHex);
		assertEquals(1, bitcoinyTransaction.inputs.size());
		assertEquals(0, bitcoinyTransaction.outputs.size());
		assertEquals(
				"483045022100eb217ddf7c671f4d9d74f02b5d5a4340ec69e7e433d6fb839dc1aa0bf8a5059b02206b45f78df09a75ad151bb4bc91e5d555098ceace53cda938bb52ff0ad34dd4a40101514c6b630400738762b17521029ddc2860644ef2a3b6c7310432e2f4231f21171e26f04150363340cd17565ac8ac6782012088a914ab08c6bf2771e0b287303cc14cc02a6bd41a1fad8821029ddc2860644ef2a3b6c7310432e2f4231f21171e26f04150363340cd17565ac8ac68",
				bitcoinyTransaction.inputs.get(0).scriptSig);
		assertEquals("a8eca1f787163374a2628d2db3521644392cb926770437eaf3fba0eec86f38fb",
				bitcoinyTransaction.inputs.get(0).outputTxHash);
		assertEquals(0, bitcoinyTransaction.inputs.get(0).outputVout);
		assertEquals(-2, bitcoinyTransaction.inputs.get(0).sequence);
		assertEquals(1653043968, bitcoinyTransaction.locktime);
	}

	@Test
	@Ignore(value = "Doesn't work, to be fixed later")
	public void testFindHtlcSecret() {
	}

	@Test
	@Ignore(value = "Needs adapting for Pirate Chain")
	public void testBuildSpend() {
	}

	@Test
	@Ignore(value = "Needs adapting for Pirate Chain")
	public void testGetWalletBalance() {
	}

	@Test
	@Ignore(value = "Needs adapting for Pirate Chain")
	public void testGetUnusedReceiveAddress() {
	}

	@Test
	@Ignore(value = "Needs adapting for Pirate Chain")
	public void testGetWalletAddresses() throws ForeignBlockchainException {
	}

	@Test
	@Ignore(value = "Needs adapting for Pirate Chain")
	public void testWalletAddressInfos() throws ForeignBlockchainException {
	}

	@Test
	@Ignore(value = "Needs adapting for Pirate Chain")
	public void testWalletSpendingCandidateAddresses() throws ForeignBlockchainException {
	}

	@Test
	@Ignore(value = "Needs adapting for Pirate Chain")
	public void testRepair() throws ForeignBlockchainException {
	}
}
