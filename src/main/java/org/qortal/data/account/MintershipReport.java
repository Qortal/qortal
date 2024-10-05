package org.qortal.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.Arrays;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class MintershipReport {

	private String address;

	private int level;

	private int blocksMinted;

	private int adjustments;

	private int penalties;

	private boolean transfer;

	private String name;

	private int sponseeCount;

	private int balance;

	private int arbitraryCount;

	private int transferAssetCount;

	private int transferPrivsCount;

	private int sellCount;

	private int sellAmount;

	private int buyCount;

	private int buyAmount;

	// Constructors

	// For JAXB
	protected MintershipReport() {
	}

	public MintershipReport(String address, int level, int blocksMinted, int adjustments, int penalties, boolean transfer, String name, int sponseeCount, int balance, int arbitraryCount, int transferAssetCount, int transferPrivsCount, int sellCount, int sellAmount, int buyCount, int buyAmount) {
		this.address = address;
		this.level = level;
		this.blocksMinted = blocksMinted;
		this.adjustments = adjustments;
		this.penalties = penalties;
		this.transfer = transfer;
		this.name = name;
		this.sponseeCount = sponseeCount;
		this.balance = balance;
		this.arbitraryCount = arbitraryCount;
		this.transferAssetCount = transferAssetCount;
		this.transferPrivsCount = transferPrivsCount;
		this.sellCount = sellCount;
		this.sellAmount = sellAmount;
		this.buyCount = buyCount;
		this.buyAmount = buyAmount;
	}

	// Getters / setters


	public String getAddress() {
		return address;
	}

	public int getLevel() {
		return level;
	}

	public int getBlocksMinted() {
		return blocksMinted;
	}

	public int getAdjustments() {
		return adjustments;
	}

	public int getPenalties() {
		return penalties;
	}

	public boolean isTransfer() {
		return transfer;
	}

	public String getName() {
		return name;
	}

	public int getSponseeCount() {
		return sponseeCount;
	}

	public int getBalance() {
		return balance;
	}

	public int getArbitraryCount() {
		return arbitraryCount;
	}

	public int getTransferAssetCount() {
		return transferAssetCount;
	}

	public int getTransferPrivsCount() {
		return transferPrivsCount;
	}

	public int getSellCount() {
		return sellCount;
	}

	public int getSellAmount() {
		return sellAmount;
	}

	public int getBuyCount() {
		return buyCount;
	}

	public int getBuyAmount() {
		return buyAmount;
	}

	@Override
	public String toString() {
		return "MintershipReport{" +
				"address='" + address + '\'' +
				", level=" + level +
				", blocksMinted=" + blocksMinted +
				", adjustments=" + adjustments +
				", penalties=" + penalties +
				", transfer=" + transfer +
				", name='" + name + '\'' +
				", sponseeCount=" + sponseeCount +
				", balance=" + balance +
				", arbitraryCount=" + arbitraryCount +
				", transferAssetCount=" + transferAssetCount +
				", transferPrivsCount=" + transferPrivsCount +
				", sellCount=" + sellCount +
				", sellAmount=" + sellAmount +
				", buyCount=" + buyCount +
				", buyAmount=" + buyAmount +
				'}';
	}
}