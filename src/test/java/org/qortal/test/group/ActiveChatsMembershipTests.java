package org.qortal.test.group;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.controller.ChatNotifier;
import org.qortal.controller.ChatTransactionDelegate;
import org.qortal.controller.Controller;
import org.qortal.data.block.BlockData;
import org.qortal.data.chat.ChatMessage;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.utils.GroupsTestUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ActiveChatsMembershipTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
		refreshAfterOrphan();
	}

	@Test
	public void testActiveChatsRefreshesForMembershipChangesAndOrphans() throws DataException {
		ChatTransactionDelegate chatDelegate = ChatTransactionDelegate.getInstance();
		AtomicInteger notifications = new AtomicInteger();
		ChatNotifier.getInstance().register(null, ignored -> notifications.incrementAndGet());

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = GroupsTestUtils.createGroup(repository, alice, "active-chats-membership", true);
			notifyNewBlock(repository);

			assertFalse(hasGroup(chatDelegate, bob.getAddress(), groupId));
			notifications.set(0);

			GroupsTestUtils.joinGroup(repository, bob, groupId);
			notifyNewBlock(repository);
			assertTrue(hasGroup(chatDelegate, bob.getAddress(), groupId));
			assertEquals(1, notifications.get());

			BlockUtils.orphanLastBlock(repository);
			notifyOrphanedBlock(repository);
			assertFalse(hasGroup(chatDelegate, bob.getAddress(), groupId));
			assertEquals(2, notifications.get());

			GroupsTestUtils.joinGroup(repository, bob, groupId);
			notifyNewBlock(repository);
			assertTrue(hasGroup(chatDelegate, bob.getAddress(), groupId));

			GroupsTestUtils.leaveGroup(repository, bob, groupId);
			notifyNewBlock(repository);
			assertFalse(hasGroup(chatDelegate, bob.getAddress(), groupId));
			assertEquals(4, notifications.get());

			BlockUtils.orphanLastBlock(repository);
			notifyOrphanedBlock(repository);
			assertTrue(hasGroup(chatDelegate, bob.getAddress(), groupId));
			assertEquals(5, notifications.get());
		} finally {
			ChatNotifier.getInstance().deregister(null);
		}
	}

	private static boolean hasGroup(ChatTransactionDelegate chatDelegate, String address, int groupId) {
		return chatDelegate.getActiveChats(address, ChatMessage.Encoding.BASE64, false).getGroups().stream()
				.anyMatch(group -> group.getGroupId() == groupId);
	}

	private static void notifyNewBlock(Repository repository) throws DataException {
		BlockData blockData = repository.getBlockRepository().getLastBlock();
		repository.discardChanges();
		Controller.getInstance().onNewBlock(blockData);
	}

	private static void notifyOrphanedBlock(Repository repository) throws DataException {
		BlockData blockData = repository.getBlockRepository().getLastBlock();
		repository.discardChanges();
		Controller.getInstance().onOrphanedBlock(blockData);
	}

	private static void refreshAfterOrphan() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			notifyOrphanedBlock(repository);
		}
	}
}
