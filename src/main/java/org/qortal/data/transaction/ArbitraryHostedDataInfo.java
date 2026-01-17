package org.qortal.data.transaction;

import org.qortal.arbitrary.misc.Service;
import org.qortal.controller.arbitrary.ArbitraryDataFolderInfo;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.List;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class ArbitraryHostedDataInfo {

	private long count;

	private long totalSpace;

	private List<ArbitraryHostedDataItemInfo> items;

	// Constructors

	// For JAXB
	protected ArbitraryHostedDataInfo() {
		super();
	}

	public ArbitraryHostedDataInfo(long count, long totalSpace, List<ArbitraryHostedDataItemInfo> items) {

		this.count = count;
		this.totalSpace = totalSpace;
		this.items = items;
	}

	public long getCount() {
		return count;
	}

	public long getTotalSpace() {
		return totalSpace;
	}

	public List<ArbitraryHostedDataItemInfo> getItems() {
		return items;
	}
}
