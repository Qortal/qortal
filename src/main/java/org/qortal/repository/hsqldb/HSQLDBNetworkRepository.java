package org.qortal.repository.hsqldb;

import org.qortal.data.network.PeerData;
import org.qortal.network.PeerAddress;
import org.qortal.network.PeerAddressFactory;
import org.qortal.network.message.MessageException;
import org.qortal.repository.DataException;
import org.qortal.repository.NetworkRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class HSQLDBNetworkRepository implements NetworkRepository {

	protected HSQLDBRepository repository;

	public HSQLDBNetworkRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	@Override
	public List<PeerData> getAllPeers() throws DataException {
		String sql = "SELECT address, last_connected, last_attempted, last_misbehaved, added_when, added_by FROM Peers";

		List<PeerData> peers = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return peers;

			// NOTE: do-while because checkedExecute() above has already called rs.next() for us
			do {
				String address = resultSet.getString(1);
				//PeerAddress peerAddress = PeerAddress.fromString(address);
                PeerAddress peerAddress = (PeerAddress) PeerAddressFactory.create("uri", address);

				Long lastConnected = resultSet.getLong(2);
				if (lastConnected == 0 && resultSet.wasNull())
					lastConnected = null;

				Long lastAttempted = resultSet.getLong(3);
				if (lastAttempted == 0 && resultSet.wasNull())
					lastAttempted = null;

				Long lastMisbehaved = resultSet.getLong(4);
				if (lastMisbehaved == 0 && resultSet.wasNull())
					lastMisbehaved = null;

				Long addedWhen = resultSet.getLong(5);
				if (addedWhen == 0 && resultSet.wasNull())
					addedWhen = null;

				String addedBy = resultSet.getString(6);

				peers.add(new PeerData(peerAddress, lastAttempted, lastConnected, lastMisbehaved, addedWhen, addedBy));
			} while (resultSet.next());

			return peers;
		} catch (IllegalArgumentException e) {
			throw new DataException("Refusing to fetch invalid peer from repository", e);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch peers from repository", e);
		} catch (ReflectiveOperationException e) {
            throw new DataException("Failed to create peer address from repository", e);
        }
	}

	@Override
	public void save(PeerData peerData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Peers");

		saveHelper.bind("address", peerData.getAddress().toString()).bind("last_connected", peerData.getLastConnected())
				.bind("last_attempted", peerData.getLastAttempted()).bind("last_misbehaved", peerData.getLastMisbehaved())
				.bind("added_when", peerData.getAddedWhen()).bind("added_by", peerData.getAddedBy());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save peer into repository", e);
		}
	}

	@Override
	public int delete(PeerAddress peerAddress) throws DataException {
		try {
			return this.repository.delete("Peers", "address = ?", peerAddress.toString());
		} catch (SQLException e) {
			throw new DataException("Unable to delete peer from repository", e);
		}
	}

	@Override
	public int deleteAllPeers() throws DataException {
		try {
			return this.repository.delete("Peers");
		} catch (SQLException e) {
			throw new DataException("Unable to delete peers from repository", e);
		}
	}

}
