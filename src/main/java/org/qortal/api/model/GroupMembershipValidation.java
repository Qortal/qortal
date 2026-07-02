package org.qortal.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@Schema(description = "Group membership validation result")
@XmlAccessorType(XmlAccessType.FIELD)
public class GroupMembershipValidation {

	public String address;
	public boolean isMember;
	public Boolean isAdmin;

	protected GroupMembershipValidation() {
	}

	public GroupMembershipValidation(String address, boolean isMember) {
		this.address = address;
		this.isMember = isMember;
	}

	public GroupMembershipValidation(String address, boolean isMember, Boolean isAdmin) {
		this.address = address;
		this.isMember = isMember;
		this.isAdmin = isAdmin;
	}

}
