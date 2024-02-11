package org.qortal.repository.hsqldb.transaction;

import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.CancelSellNameTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.hsqldb.HSQLDBRepository;
import org.qortal.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBCancelSellNameTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBCancelSellNameTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT name, sale_price, is_private_sale, sale_recipient FROM CancelSellNameTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			String name = resultSet.getString(1);
			Long salePrice = resultSet.getLong(2);
			boolean isPrivateSale = resultSet.getBoolean(3);
			String saleRecipient = resultSet.getString(4);
			if (!isPrivateSale)
				saleRecipient = null;

			return new CancelSellNameTransactionData(baseTransactionData, name, salePrice, isPrivateSale, saleRecipient);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch cancel sell name transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		CancelSellNameTransactionData cancelSellNameTransactionData = (CancelSellNameTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("CancelSellNameTransactions");

		saveHelper.bind("signature", cancelSellNameTransactionData.getSignature()).bind("owner", cancelSellNameTransactionData.getOwnerPublicKey()).bind("name",
				cancelSellNameTransactionData.getName()).bind("sale_price", cancelSellNameTransactionData.getSalePrice()).bind("is_private_sale", cancelSellNameTransactionData.getIsPrivateSale()).bind("sale_recipient", cancelSellNameTransactionData.getSaleRecipient());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save cancel sell name transaction into repository", e);
		}
	}

}
