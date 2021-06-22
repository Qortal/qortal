package org.qortal.network;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.controller.Controller;
import org.qortal.crypto.Crypto;
import org.qortal.crypto.MemoryPoW;
import org.qortal.network.message.ChallengeMessage;
import org.qortal.network.message.HelloMessage;
import org.qortal.network.message.Message;
import org.qortal.network.message.MessageType;
import org.qortal.settings.Settings;
import org.qortal.network.message.ResponseMessage;
import org.qortal.utils.DaemonThreadFactory;
import org.qortal.utils.NTP;

import com.google.common.primitives.Bytes;

public enum Handshake {
	STARTED(null) {
		@Override
		public Handshake onMessage(Peer peer, Message message) {
			return HELLO;
		}

		@Override
		public void action(Peer peer) {
			/* Never called */
		}
	},
	HELLO(MessageType.HELLO) {
		@Override
		public Handshake onMessage(Peer peer, Message message) {
			HelloMessage helloMessage = (HelloMessage) message;

			long peersConnectionTimestamp = helloMessage.getTimestamp();
			long now = NTP.getTime();

			long timestampDelta = Math.abs(peersConnectionTimestamp - now);
			if (timestampDelta > MAX_TIMESTAMP_DELTA) {
				LOGGER.debug(() -> String.format("Peer %s HELLO timestamp %d too divergent (± %d > %d) from ours %d",
						peer, peersConnectionTimestamp, timestampDelta, MAX_TIMESTAMP_DELTA, now));
				return null;
			}

			String versionString = helloMessage.getVersionString();

			Matcher matcher = peer.VERSION_PATTERN.matcher(versionString);
			if (!matcher.lookingAt()) {
				LOGGER.debug(() -> String.format("Peer %s sent invalid HELLO version string '%s'", peer, versionString));
				return null;
			}

			// We're expecting 3 positive shorts, so we can convert 1.2.3 into 0x0100020003
			long version = 0;
			for (int g = 1; g <= 3; ++g) {
				long value = Long.parseLong(matcher.group(g));

				if (value < 0 || value > Short.MAX_VALUE)
					return null;

				version <<= 16;
				version |= value;
			}

			peer.setPeersConnectionTimestamp(peersConnectionTimestamp);
			peer.setPeersVersion(versionString, version);

			if (Settings.getInstance().getAllowConnectionsWithOlderPeerVersions() == false) {
				// Ensure the peer is running at least the minimum version allowed for connections
				final String minPeerVersion = Settings.getInstance().getMinPeerVersion();
				if (peer.isAtLeastVersion(minPeerVersion) == false) {
					LOGGER.debug(String.format("Ignoring peer %s because it is on an old version (%s)", peer, versionString));
					return null;
				}
			}

			return CHALLENGE;
		}

		@Override
		public void action(Peer peer) {
			String versionString = Controller.getInstance().getVersionString();
			long timestamp = NTP.getTime();

			Message helloMessage = new HelloMessage(timestamp, versionString);
			if (!peer.sendMessage(helloMessage))
				peer.disconnect("failed to send HELLO");
		}
	},
	CHALLENGE(MessageType.CHALLENGE) {
		@Override
		public Handshake onMessage(Peer peer, Message message) {
			ChallengeMessage challengeMessage = (ChallengeMessage) message;

			byte[] peersPublicKey = challengeMessage.getPublicKey();
			byte[] peersChallenge = challengeMessage.getChallenge();

			// If public key matches our public key then we've connected to self
			byte[] ourPublicKey = Network.getInstance().getOurPublicKey();
			if (Arrays.equals(ourPublicKey, peersPublicKey)) {
				// If outgoing connection then record destination as self so we don't try again
				if (peer.isOutbound()) {
					Network.getInstance().noteToSelf(peer);
					// Handshake failure, caller will handle disconnect
					return null;
				} else {
					// We still need to send our ID so our outbound connection can mark their address as 'self'
					challengeMessage = new ChallengeMessage(ourPublicKey, ZERO_CHALLENGE);
					if (!peer.sendMessage(challengeMessage))
						peer.disconnect("failed to send CHALLENGE to self");

					/*
					 * We return CHALLENGE here to prevent us from closing connection. Closing
					 * connection currently preempts remote end from reading any pending messages,
					 * specifically the CHALLENGE message we just sent above. When our 'remote'
					 * outbound counterpart reads our message, they will close both connections.
					 * Failing that, our connection will timeout or a future handshake error will
					 * occur.
					 */
					return CHALLENGE;
				}
			}

			// Are we already connected to this peer?
			Peer existingPeer = Network.getInstance().getHandshakedPeerWithPublicKey(peersPublicKey);
			if (existingPeer != null) {
				LOGGER.info(() -> String.format("We already have a connection with peer %s - discarding", peer));
				// Handshake failure - caller will deal with disconnect
				return null;
			}

			peer.setPeersPublicKey(peersPublicKey);
			peer.setPeersChallenge(peersChallenge);

			return RESPONSE;
		}

		@Override
		public void action(Peer peer) {
			// Send challenge
			byte[] publicKey = Network.getInstance().getOurPublicKey();
			byte[] challenge = peer.getOurChallenge();

			Message challengeMessage = new ChallengeMessage(publicKey, challenge);
			if (!peer.sendMessage(challengeMessage))
				peer.disconnect("failed to send CHALLENGE");
		}
	},
	RESPONSE(MessageType.RESPONSE) {
		@Override
		public Handshake onMessage(Peer peer, Message message) {
			ResponseMessage responseMessage = (ResponseMessage) message;

			byte[] peersPublicKey = peer.getPeersPublicKey();
			byte[] ourChallenge = peer.getOurChallenge();

			byte[] sharedSecret = Network.getInstance().getSharedSecret(peersPublicKey);
			final byte[] expectedData = Crypto.digest(Bytes.concat(sharedSecret, ourChallenge));

			byte[] data = responseMessage.getData();
			if (!Arrays.equals(expectedData, data)) {
				LOGGER.debug(() -> String.format("Peer %s sent incorrect RESPONSE data", peer));
				return null;
			}

			int nonce = responseMessage.getNonce();
			int powBufferSize = peer.getPeersVersion() < PEER_VERSION_131 ? POW_BUFFER_SIZE_PRE_131 : POW_BUFFER_SIZE_POST_131;
			int powDifficulty = peer.getPeersVersion() < PEER_VERSION_131 ? POW_DIFFICULTY_PRE_131 : POW_DIFFICULTY_POST_131;
			if (!MemoryPoW.verify2(data, powBufferSize, powDifficulty, nonce)) {
				LOGGER.debug(() -> String.format("Peer %s sent incorrect RESPONSE nonce", peer));
				return null;
			}

			peer.setPeersNodeId(Crypto.toNodeAddress(peersPublicKey));

			// For inbound peers, we need to go into interim holding state while we compute RESPONSE
			if (!peer.isOutbound())
				return RESPONDING;

			// Handshake completed!
			return COMPLETED;
		}

		@Override
		public void action(Peer peer) {
			// Send response

			byte[] peersPublicKey = peer.getPeersPublicKey();
			byte[] peersChallenge = peer.getPeersChallenge();

			byte[] sharedSecret = Network.getInstance().getSharedSecret(peersPublicKey);
			final byte[] data = Crypto.digest(Bytes.concat(sharedSecret, peersChallenge));

			// We do this in a new thread as it can take a while...
			responseExecutor.execute(() -> {
				// Are we still connected?
				if (peer.isStopping())
					// No point computing for dead peer
					return;

				int powBufferSize = peer.getPeersVersion() < PEER_VERSION_131 ? POW_BUFFER_SIZE_PRE_131 : POW_BUFFER_SIZE_POST_131;
				int powDifficulty = peer.getPeersVersion() < PEER_VERSION_131 ? POW_DIFFICULTY_PRE_131 : POW_DIFFICULTY_POST_131;
				Integer nonce = MemoryPoW.compute2(data, powBufferSize, powDifficulty);

				Message responseMessage = new ResponseMessage(nonce, data);
				if (!peer.sendMessage(responseMessage))
					peer.disconnect("failed to send RESPONSE");

				// For inbound peers, we should actually be in RESPONDING state.
				// So we need to do the extra work to move to COMPLETED state.
				if (!peer.isOutbound()) {
					peer.setHandshakeStatus(COMPLETED);
					Network.getInstance().onHandshakeCompleted(peer);
				}
			});
		}
	},
	// Interim holding state while we compute RESPONSE to send to inbound peer
	RESPONDING(null) {
		@Override
		public Handshake onMessage(Peer peer, Message message) {
			// Should never be called
			return null;
		}

		@Override
		public void action(Peer peer) {
			// Should never be called
		}
	},
	COMPLETED(null) {
		@Override
		public Handshake onMessage(Peer peer, Message message) {
			// Should never be called
			return null;
		}

		@Override
		public void action(Peer peer) {
			// Note: this is only called if we've made outbound connection
		}
	};

	private static final Logger LOGGER = LogManager.getLogger(Handshake.class);

	/** Maximum allowed difference between peer's reported timestamp and when they connected, in milliseconds. */
	private static final long MAX_TIMESTAMP_DELTA = 30 * 1000L; // ms

	private static final long PEER_VERSION_131 = 0x0100030001L;

	private static final int POW_BUFFER_SIZE_PRE_131 = 8 * 1024 * 1024; // bytes
	private static final int POW_DIFFICULTY_PRE_131 = 8; // leading zero bits
	// Can always be made harder in the future...
	private static final int POW_BUFFER_SIZE_POST_131 = 2 * 1024 * 1024; // bytes
	private static final int POW_DIFFICULTY_POST_131 = 2; // leading zero bits


	private static final ExecutorService responseExecutor = Executors.newFixedThreadPool(Settings.getInstance().getNetworkPoWComputePoolSize(), new DaemonThreadFactory("Network-PoW"));

	private static final byte[] ZERO_CHALLENGE = new byte[ChallengeMessage.CHALLENGE_LENGTH];

	public final MessageType expectedMessageType;

	private Handshake(MessageType expectedMessageType) {
		this.expectedMessageType = expectedMessageType;
	}

	public abstract Handshake onMessage(Peer peer, Message message);

	public abstract void action(Peer peer);

}
