package org.qortal.repository;

import org.qortal.arbitrary.misc.Service;
import org.qortal.data.arbitrary.ArbitraryResourceInfo;
import org.qortal.data.arbitrary.ArbitraryResourceNameInfo;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.Method;

import java.util.List;

public interface ArbitraryRepository {

	public boolean isDataLocal(byte[] signature) throws DataException;

	public byte[] fetchData(byte[] signature) throws DataException;

	public void save(ArbitraryTransactionData arbitraryTransactionData) throws DataException;

	public void delete(ArbitraryTransactionData arbitraryTransactionData) throws DataException;

	public List<ArbitraryTransactionData> getArbitraryTransactions(String name, Service service, String identifier, long since) throws DataException;

	public ArbitraryTransactionData getLatestTransaction(String name, Service service, Method method, String identifier) throws DataException;


	public List<ArbitraryResourceInfo> getArbitraryResources(Service service, String identifier, List<String> names, boolean defaultResource, Boolean followedOnly, Boolean excludeBlocked, Integer limit, Integer offset, Boolean reverse) throws DataException;

	public List<ArbitraryResourceInfo> searchArbitraryResources(Service service, String query, String identifier, List<String> names, boolean prefixOnly, List<String> namesFilter, boolean defaultResource, Boolean followedOnly, Boolean excludeBlocked, Integer limit, Integer offset, Boolean reverse) throws DataException;

	public List<ArbitraryResourceNameInfo> getArbitraryResourceCreatorNames(Service service, String identifier, boolean defaultResource, Integer limit, Integer offset, Boolean reverse) throws DataException;

}
