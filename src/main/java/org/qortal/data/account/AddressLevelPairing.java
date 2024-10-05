package org.qortal.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.Arrays;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class AddressLevelPairing {

	private String address;

	private int level;

	// Constructors

	// For JAXB
	protected AddressLevelPairing() {
	}

	public AddressLevelPairing(String address, int level) {
		this.address = address;
		this.level = level;
	}

	// Getters / setters


	public String getAddress() {
		return address;
	}

	public int getLevel() {
		return level;
	}
	@Override
	public String toString() {
		return "SponsorshipReport{" +
				"address='" + address + '\'' +
				", level=" + level +
				'}';
	}
}