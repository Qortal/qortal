package org.qortal.data.chat;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class ChatStat {

	/* Address of sender */
	private String sender;

	// Not always present
	private String senderName;

	private long size;

	// Constructors

	protected ChatStat() {
		/* For JAXB */
	}

	// For repository use
	public ChatStat(String sender, String senderName, long size) {
		this.sender = sender;
		this.senderName = senderName;
		this.size = size;
	}

	public String getSender() {
		return this.sender;
	}

	public String getSenderName() {
		return this.senderName;
	}

	public long getSize() {
		return size;
	}
}
