package org.qortal.test.crosschain.apps;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.qortal.crosschain.Bitcoin;
import org.qortal.crosschain.Bitcoiny;
import org.qortal.crosschain.Litecoin;

public class Pay {

	private static void usage(String error) {
		if (error != null)
			System.err.println(error);

		System.err.println(String.format("usage: Pay (-b | -l) <xprv58> <recipient> <LTC-amount>"));
		System.err.println("where: -b means use Bitcoin, -l means use Litecoin");
		System.err.println(String.format("example: Pay -l "
				+ "tprv8ZgxMBicQKsPdahhFSrCdvC1bsWyzHHZfTneTVqUXN6s1wEtZLwAkZXzFP6TYLg2aQMecZLXLre5bTVGajEB55L1HYJcawpdFG66STVAWPJ \\\n"
				+ "\tmsAfaDaJ8JiprxxFaAXEEPxKK3JaZCYpLv \\\n"
				+ "\t0.00008642"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length < 4 || args.length > 4)
			usage(null);

		Common.init();

		Bitcoiny bitcoiny = null;
		NetworkParameters params = null;

		String xprv58 = null;
		Address address = null;
		Coin amount = null;

		int argIndex = 0;
		try {
			switch (args[argIndex++]) {
				case "-b":
					bitcoiny = Bitcoin.getInstance();
					break;

				case "-l":
					bitcoiny = Litecoin.getInstance();
					break;

				default:
					usage("Only Bitcoin (-b) or Litecoin (-l) supported");
			}
			params = bitcoiny.getNetworkParameters();

			xprv58 = args[argIndex++];
			if (!bitcoiny.isValidDeterministicKey(xprv58))
				usage("xprv invalid");

			address = Address.fromString(params, args[argIndex++]);

			amount = Coin.parseCoin(args[argIndex++]);
		} catch (IllegalArgumentException e) {
			usage(String.format("Invalid argument %d: %s", argIndex, e.getMessage()));
		}

		System.out.println(String.format("Using %s", bitcoiny.getBlockchainProvider().getNetId()));

		System.out.println(String.format("Address: %s", address));
		System.out.println(String.format("Amount: %s", amount.toPlainString()));

		Transaction transaction = bitcoiny.buildSpend(xprv58, address.toString(), amount.value);
		if (transaction == null) {
			System.err.println("Insufficient funds");
			System.exit(1);
		}

		Common.broadcastTransaction(bitcoiny, transaction);
	}

}
