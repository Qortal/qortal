package org.qortal.crosschain;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.Objects;

@XmlAccessorType(XmlAccessType.FIELD)
public class RepairWalletPreview {
	private long oldBalance;
	private long currentBalance;
	private long missingBalance;
	private long estimatedFee;
	private long dustThreshold;
	private boolean repairRecommended;
	private int addressCountOld;
	private int addressCountCurrent;
	private int missingUtxoCount;

	public RepairWalletPreview() {
	}

	public RepairWalletPreview(
			long oldBalance,
			long currentBalance,
			long missingBalance,
			long estimatedFee,
			long dustThreshold,
			boolean repairRecommended,
			int addressCountOld,
			int addressCountCurrent,
			int missingUtxoCount) {
		this.oldBalance = oldBalance;
		this.currentBalance = currentBalance;
		this.missingBalance = missingBalance;
		this.estimatedFee = estimatedFee;
		this.dustThreshold = dustThreshold;
		this.repairRecommended = repairRecommended;
		this.addressCountOld = addressCountOld;
		this.addressCountCurrent = addressCountCurrent;
		this.missingUtxoCount = missingUtxoCount;
	}

	public long getOldBalance() {
		return oldBalance;
	}

	public long getCurrentBalance() {
		return currentBalance;
	}

	public long getMissingBalance() {
		return missingBalance;
	}

	public long getEstimatedFee() {
		return estimatedFee;
	}

	public long getDustThreshold() {
		return dustThreshold;
	}

	public boolean isRepairRecommended() {
		return repairRecommended;
	}

	public int getAddressCountOld() {
		return addressCountOld;
	}

	public int getAddressCountCurrent() {
		return addressCountCurrent;
	}

	public int getMissingUtxoCount() {
		return missingUtxoCount;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		RepairWalletPreview that = (RepairWalletPreview) o;
		return oldBalance == that.oldBalance
				&& currentBalance == that.currentBalance
				&& missingBalance == that.missingBalance
				&& estimatedFee == that.estimatedFee
				&& dustThreshold == that.dustThreshold
				&& repairRecommended == that.repairRecommended
				&& addressCountOld == that.addressCountOld
				&& addressCountCurrent == that.addressCountCurrent
				&& missingUtxoCount == that.missingUtxoCount;
	}

	@Override
	public int hashCode() {
		return Objects.hash(oldBalance, currentBalance, missingBalance, estimatedFee, dustThreshold, repairRecommended, addressCountOld, addressCountCurrent, missingUtxoCount);
	}

	@Override
	public String toString() {
		return "RepairWalletPreview{" +
				"oldBalance=" + oldBalance +
				", currentBalance=" + currentBalance +
				", missingBalance=" + missingBalance +
				", estimatedFee=" + estimatedFee +
				", dustThreshold=" + dustThreshold +
				", repairRecommended=" + repairRecommended +
				", addressCountOld=" + addressCountOld +
				", addressCountCurrent=" + addressCountCurrent +
				", missingUtxoCount=" + missingUtxoCount +
				'}';
	}
}
