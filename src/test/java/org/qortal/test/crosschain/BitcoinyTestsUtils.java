package org.qortal.test.crosschain;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDPath;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.Litecoin;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

public class BitcoinyTestsUtils {

    public static void main(String[] args) throws DataException, ForeignBlockchainException {

        Common.useDefaultSettings();

        final String rootKey = generateBip32RootKey( Litecoin.LitecoinNet.TEST3.getParams());
        String address = Litecoin.getInstance().getUnusedReceiveAddress(rootKey);

        System.out.println("rootKey = " + rootKey);
        System.out.println("address = " + address);

        System.exit(0);
    }

    public static String generateBip32RootKey(NetworkParameters networkParameters) {

        final Wallet wallet = Wallet.createDeterministic(networkParameters, Script.ScriptType.P2PKH);
        final DeterministicSeed seed = wallet.getKeyChainSeed();
        final DeterministicKeyChain keyChain = DeterministicKeyChain.builder().seed(seed).build();
        final HDPath path = keyChain.getAccountPath();
        final DeterministicKey parent = keyChain.getKeyByPath(path, true);
        final String rootKey = parent.serializePrivB58(networkParameters);

        return rootKey;
    }
}