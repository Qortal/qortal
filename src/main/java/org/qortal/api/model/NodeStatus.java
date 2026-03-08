package org.qortal.api.model;

import org.qortal.controller.Controller;
import org.qortal.controller.OnlineAccountsManager;
import org.qortal.controller.Synchronizer;
import org.qortal.network.Network;
import org.qortal.network.NetworkData;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class NodeStatus {

	public final boolean isMintingPossible;
	public final boolean isSynchronizing;

	// Not always present
	public final Integer syncPercent;

	public final int numberOfConnections;

	public final int numberOfDataConnections;

	public final int height;

	public NodeStatus() {
		this.isMintingPossible = OnlineAccountsManager.getInstance().hasActiveOnlineAccountSignatures();

		this.syncPercent = Synchronizer.getInstance().getSyncPercent();
		this.isSynchronizing = Synchronizer.getInstance().isSynchronizing();

		this.numberOfConnections = Network.getInstance().getImmutableHandshakedPeers().size();

		this.numberOfDataConnections = NetworkData.getInstance().getImmutableHandshakedPeers().size();

		this.height = Controller.getInstance().getChainHeight();
	}

}
