package org.qortal.crosschain;

import org.qortal.data.at.ATData;
import org.qortal.data.at.ATStateData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

import java.util.List;
import java.util.OptionalLong;

public interface ACCT {

	public byte[] getCodeBytesHash();

	public int getModeByteOffset();

	public ForeignBlockchain getBlockchain();

	public CrossChainTradeData populateTradeData(Repository repository, ATData atData) throws DataException;

	public List<CrossChainTradeData> populateTradeDataList(Repository repository, List<ATData> atDataList) throws DataException;

	public CrossChainTradeData populateTradeData(Repository repository, ATStateData atStateData) throws DataException;

	CrossChainTradeData populateTradeData(Repository repository, byte[] creatorPublicKey, long creationTimestamp, ATStateData atStateData, OptionalLong optionalBalance) throws DataException;

	public byte[] buildCancelMessage(String creatorQortalAddress);

	public byte[] findSecretA(Repository repository, CrossChainTradeData crossChainTradeData) throws DataException;

}
