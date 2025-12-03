package org.qortal.data.transaction;

import org.qortal.arbitrary.misc.Service;
import org.qortal.controller.arbitrary.ArbitraryDataFolderInfo;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class ArbitraryHostedDataItemInfo {
	private int chunkCount;
	private long totalSpace;
	private ArbitraryTransactionData.DataType dataType;
	private ArbitraryTransactionData.Compression compression;
	private ArbitraryTransactionData.Method method;
	private String identifier;
	private long timestamp;
	private byte[] signature;
	private String creatorAddress;
	private Service service;
	private int size;
	private String name;

	// Constructors

	// For JAXB
	protected ArbitraryHostedDataItemInfo() {
		super();
	}

	public ArbitraryHostedDataItemInfo(ArbitraryTransactionData data, ArbitraryDataFolderInfo info) {

		this.timestamp = data.getTimestamp();
		this.signature = data.getSignature();
		this.creatorAddress = data.getCreatorAddress();
		this.service = data.getService();
		this.size = data.getSize();
		this.name = data.getName();
		this.identifier = data.getIdentifier();
		this.method = data.getMethod();
		this.compression = data.getCompression();
		this.dataType = data.getDataType();

		this.totalSpace = info.getTotalSpace();
		this.chunkCount = info.getChunkCount();
	}

	public int getChunkCount() {
		return chunkCount;
	}

	public long getTotalSpace() {
		return totalSpace;
	}

	public ArbitraryTransactionData.DataType getDataType() {
		return dataType;
	}

	public ArbitraryTransactionData.Compression getCompression() {
		return compression;
	}

	public ArbitraryTransactionData.Method getMethod() {
		return method;
	}

	public String getIdentifier() {
		return identifier;
	}

	public long getTimestamp() {
		return timestamp;
	}

	public byte[] getSignature() {
		return signature;
	}

	public String getCreatorAddress() {
		return creatorAddress;
	}

	public Service getService() {
		return service;
	}

	public int getSize() {
		return size;
	}

	public String getName() {
		return name;
	}
}
