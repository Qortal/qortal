package org.qortal.repository;

import org.qortal.api.SearchMode;
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.arbitrary.ArbitraryResourceData;
import org.qortal.data.arbitrary.ArbitraryResourceMetadata;
import org.qortal.data.arbitrary.ArbitraryResourceStatus;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ArbitraryTransactionData.Method;

import java.util.List;

public interface ArbitraryRepository {

	// Utils

	public boolean isDataLocal(byte[] signature) throws DataException;

	public byte[] fetchData(byte[] signature) throws DataException;


	// Transaction related

	public void save(ArbitraryTransactionData arbitraryTransactionData) throws DataException;

	public void delete(ArbitraryTransactionData arbitraryTransactionData) throws DataException;

	public List<ArbitraryTransactionData> getArbitraryTransactions(String name, Service service, String identifier, long since) throws DataException;

	public ArbitraryTransactionData getInitialTransaction(String name, Service service, Method method, String identifier) throws DataException;

	public ArbitraryTransactionData getLatestTransaction(String name, Service service, Method method, String identifier) throws DataException;

	public List<ArbitraryTransactionData> getArbitraryTransactions(boolean requireName, Integer limit, Integer offset, Boolean reverse) throws DataException;


	// Resource related

	public ArbitraryResourceData getArbitraryResource(Service service, String name, String identifier) throws DataException;

	public List<ArbitraryResourceData> getArbitraryResources(Integer limit, Integer offset, Boolean reverse) throws DataException;

	public List<ArbitraryResourceData> getArbitraryResources(Service service, String identifier, List<String> names, boolean defaultResource, Boolean followedOnly, Boolean excludeBlocked, Boolean includeMetadata, Boolean includeStatus, Integer limit, Integer offset, Boolean reverse) throws DataException;

	public List<ArbitraryResourceData> searchArbitraryResources(Service service, String query, String identifier, List<String> names, String title, String description, boolean prefixOnly, List<String> namesFilter, boolean defaultResource, SearchMode mode, Integer minLevel, Boolean followedOnly, Boolean excludeBlocked, Boolean includeMetadata, Boolean includeStatus, Long before, Long after, Integer limit, Integer offset, Boolean reverse) throws DataException;

	List<ArbitraryResourceData> searchArbitraryResourcesSimple(
			Service service,
			String identifier,
			List<String> names,
			boolean prefixOnly,
			Long before,
			Long after,
			Integer limit,
			Integer offset,
			Boolean reverse,
			Boolean caseInsensitive) throws DataException;

	// Arbitrary resources cache save/load

	public void save(ArbitraryResourceData arbitraryResourceData) throws DataException;
	public void setStatus(ArbitraryResourceData arbitraryResourceData, ArbitraryResourceStatus.Status status) throws DataException;
	public void delete(ArbitraryResourceData arbitraryResourceData) throws DataException;

	public void save(ArbitraryResourceMetadata metadata) throws DataException;
	public void delete(ArbitraryResourceMetadata metadata) throws DataException;
}
