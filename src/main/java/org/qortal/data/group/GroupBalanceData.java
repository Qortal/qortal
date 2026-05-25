package org.qortal.data.group;

import org.qortal.group.Group;

public class GroupBalanceData extends GroupData{

    private long balance;

    public GroupBalanceData() {
    }

    public GroupBalanceData(Integer groupId, String owner, String groupName, String description, long created, Long updated, boolean isOpen, Group.ApprovalThreshold approvalThreshold, int minBlockDelay, int maxBlockDelay, byte[] reference, int creationGroupId, String reducedGroupName, int memberCount, long balance) {
        super(groupId, owner, groupName, description, created, updated, isOpen, approvalThreshold, minBlockDelay, maxBlockDelay, reference, creationGroupId, reducedGroupName);

        this.memberCount = memberCount;
        this.balance = balance;
    }

    public GroupBalanceData(String owner, String groupName, String description, long created, boolean isOpen, Group.ApprovalThreshold approvalThreshold, int minBlockDelay, int maxBlockDelay, byte[] reference, int creationGroupId, String reducedGroupName, int memberCount, long balance) {
        super(owner, groupName, description, created, isOpen, approvalThreshold, minBlockDelay, maxBlockDelay, reference, creationGroupId, reducedGroupName);

        this.memberCount = memberCount;
        this.balance = balance;
    }

    public long getBalance() {
        return balance;
    }
}
