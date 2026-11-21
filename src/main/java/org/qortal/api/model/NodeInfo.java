package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class NodeInfo {

	public Long currentTimestamp;
	public long uptime;
	public String buildVersion;
	public long buildTimestamp;
	public String nodeId;
	public boolean isTestNet;
	/** Effective wallet network; independent of Qortal consensus/network selection. */
	public String moneroNetwork;
	public String type;

	public NodeInfo() {
	}

}
