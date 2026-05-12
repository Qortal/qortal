package org.qortal.test.utils;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.CreateGroupTransactionData;
import org.qortal.data.transaction.GroupInviteTransactionData;
import org.qortal.data.transaction.JoinGroupTransactionData;
import org.qortal.data.transaction.LeaveGroupTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;

/**
 * Class GroupsTestUtils
 *
 * Utility methods for testing the Groups class.
 */
public class GroupsTestUtils {

    /**
     * Create Group
     *
     * @param repository the data repository
     * @param owner the group owner
     * @param groupName the group name
     * @param isOpen true if the group is public, false for private
     *
     * @return the group Id
     * @throws DataException
     */
    public static Integer createGroup(Repository repository, PrivateKeyAccount owner, String groupName, boolean isOpen) throws DataException {
        String description = groupName + " (description)";

        Group.ApprovalThreshold approvalThreshold = Group.ApprovalThreshold.ONE;
        int minimumBlockDelay = 10;
        int maximumBlockDelay = 1440;

        CreateGroupTransactionData transactionData = new CreateGroupTransactionData(TestTransaction.generateBase(owner), groupName, description, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay, 0);
        TransactionUtils.signAndMint(repository, transactionData, owner);

        return repository.getGroupRepository().fromGroupName(groupName).getGroupId();
    }

    /**
     * Join Group
     *
     * @param repository the data repository
     * @param joiner the address for the account joining the group
     * @param groupId the Id for the group to join
     *
     * @throws DataException
     */
    public static void joinGroup(Repository repository, PrivateKeyAccount joiner, int groupId) throws DataException {
        JoinGroupTransactionData transactionData = new JoinGroupTransactionData(TestTransaction.generateBase(joiner), groupId);
        TransactionUtils.signAndMint(repository, transactionData, joiner);
    }

    /**
     * Group Invite
     *
     * @param repository the data repository
     * @param admin the admin account to sign the invite
     * @param groupId the Id of the group to invite to
     * @param invitee the recipient address for the invite
     * @param timeToLive the time length of the invite
     *
     * @throws DataException
     */
    public static void groupInvite(Repository repository, PrivateKeyAccount admin, int groupId, String invitee, int timeToLive) throws DataException {
        GroupInviteTransactionData transactionData = new GroupInviteTransactionData(TestTransaction.generateBase(admin), groupId, invitee, timeToLive, 0L);
        TransactionUtils.signAndMint(repository, transactionData, admin);
    }

    /**
     * Leave Group
     *
     * @param repository the data repository
     * @param leaver the account leaving
     * @param groupId the Id of the group being left
     *
     * @throws DataException
     */
    public static void leaveGroup(Repository repository, PrivateKeyAccount leaver, int groupId) throws DataException {
        LeaveGroupTransactionData transactionData = new LeaveGroupTransactionData(TestTransaction.generateBase(leaver), groupId);
        TransactionUtils.signAndMint(repository, transactionData, leaver);
    }

    /**
     * Is Member?
     *
     * @param repository the data repository
     * @param address the account address
     * @param groupId the group Id
     *
     * @return true if the account is a member of the group, otherwise false
     * @throws DataException
     */
    public static boolean isMember(Repository repository, String address, int groupId) throws DataException {
        return repository.getGroupRepository().memberExists(groupId, address);
    }
}