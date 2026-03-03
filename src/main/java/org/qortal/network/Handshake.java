package org.qortal.network;

import com.google.common.primitives.Bytes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.controller.Controller;
import org.qortal.crypto.Crypto;
import org.qortal.crypto.MemoryPoW;
import org.qortal.network.message.*;
import org.qortal.settings.Settings;
import org.qortal.utils.DaemonThreadFactory;
import org.qortal.utils.NTP;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;

/**
 * Handshake
 * =========
 *
 * Implements the full peer-to-peer (for P2P & QDN) connection processing using 
 * a chain of handshake ENUM as a deterministic, state-driven (finite) machine.
 *
 * This enum models both inbound and outbound handshakes and coordinates message
 * exchange, validation, Proof-of-Work (PoW), and duplicate-connection resolution
 * for P2P Network and Qortal Data Network (QDN) peers.
 *
 * ---------------------------------------------------------------------------
 * HIGH-LEVEL WORKFLOW
 * ---------------------------------------------------------------------------
 *
 * 1. STARTED
 *    - Initial state when socket connection is initiated or established.
 *    - Immediately transitions to HELLO upon connection.
 *
 * 2. HELLO / HELLO_V2
 *    - Exchange basic peer identity:
 *        • node version
 *        • connection timestamp (NTP-validated)
 *        • sender address
 *        • optional capabilities (HELLO_V2)
 *    - Rejects peers with:
 *        • excessive clock/ntp offset or skew
 *        • invalid version string
 *        • unsupported / too old version
 *    - HELLO_V2 is conditionally used for peers >= HELLO_V2_MIN_VERSION.
 *
 * 3. CHALLENGE
 *    - Mutual authentication phase.
 *    - Peers exchange public keys and random challenges.
 *    - Detects and handles:
 *        • self-connections (disconnects)
 *        • duplicate connections (disconnects)
 *        • ghost peers (handshaked but not connected) (terminates)
 *        • stale half-open connections (terminates)
 *    - Uses deterministic tie-breaking based on nodeId comparison to prevent
 *      reconnect loops.
 *    - Includes outbound-failure fallback logic to improve reachability.
 *
 * 4. RESPONSE
 *    - Proof-of-Work–protected authentication.
 *    - Each side computes:
 *        • digest(sharedSecret or challenge)
 *    - PoW difficulty and buffer size vary by peer version.
 *    - RX (message thread) validates peer RESPONSE.
 *    - TX (PoW thread) computes and sends our RESPONSE asynchronously.
 *
 * 5. RESPONDING
 *    - Transitional holding state while PoW computation is in progress.
 *    - Completion is coordinated via tryCompleteHandshake().
 *
 * 6. COMPLETED
 *    - Handshake successfully finalized.
 *    - Ownership is handed off to P2P Network or Qortal Data Networ via 
 * 		onHandshakeCompleted().
 *
 * ---------------------------------------------------------------------------
 * CONCURRENCY MODEL
 * ---------------------------------------------------------------------------
 *
 * - RX path: message-processing thread validates incoming data.
 * - TX path: PoW computation runs in a dedicated executor.
 * - Handshake completion is centralized and guaranteed to run exactly once,
 *   regardless of which side (RX or TX) finishes first.
 *
 * ---------------------------------------------------------------------------
 * DESIGN GOALS
 * ---------------------------------------------------------------------------
 *
 * - Deterministic behavior (no connection churn or reconnect loops)
 * - Termination handling of stale, duplicate, and half-open connections
 * - Backward compatibility with older peer versions
 * - Minimal trust: all critical data is cryptographically verified
 *
 * This file is self-contained: the enum states define the
 * protocol transitions and the side effects at each stage.
 */

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
			if (message.getType() == MessageType.HELLO_V2) {
				return processHelloV2Message(peer, (HelloV2Message) message);
			}
			return processHelloMessage(peer, (HelloMessage) message);
		}

		@Override
		public void action(Peer peer) {
			boolean sendHelloV2 = peer.isAwaitingHelloV2Response() && peer.isAtLeastVersion(HELLO_V2_MIN_VERSION);
			if (!sendHello(peer, sendHelloV2)) {
				return;
			}
			LOGGER.debug("OUTBOUND - FINISHED sending {}: Network {}", sendHelloV2 ? "HELLO_V2" : "HELLO", peer.getPeerType());
		}
	},
	HELLO_V2(MessageType.HELLO_V2) {
		@Override
		public Handshake onMessage(Peer peer, Message message) {
			return processHelloV2Message(peer, (HelloV2Message) message);
		}

		@Override
		public void action(Peer peer) {
			// No additional messages to send from this state
		}
	},
	CHALLENGE(MessageType.CHALLENGE) {
		@Override
		public Handshake onMessage(Peer peer, Message message) {
			ChallengeMessage challengeMessage = (ChallengeMessage) message;
			LOGGER.debug("STARTING RX of CHALLENGE on {}, from {}", peer.getPeerType(), peer.getPeerData().getAddress());
			byte[] peersPublicKey = challengeMessage.getPublicKey();
			byte[] peersChallenge = challengeMessage.getChallenge();

			// If public key matches our public key then we've connected to self
			// CRITICAL: Check for null to prevent NPE during Network initialization
			// Use the key for this connection type (Network vs NetworkData)
			byte[] ourPublicKey;
			switch (peer.getPeerType()) {
				case Peer.NETWORKDATA:
					ourPublicKey = NetworkData.getInstance().getOurPublicKey();
					break;
				case Peer.NETWORK:
				default:
					ourPublicKey = Network.getInstance().getOurPublicKey();
					break;
			}
			if (ourPublicKey != null && Arrays.equals(ourPublicKey, peersPublicKey)) {
				// If outgoing connection then record destination as self so we don't try again
				if (peer.isOutbound()) {
					switch (peer.getPeerType()) {
						case Peer.NETWORK:
							Network.getInstance().noteToSelf(peer);
							break;
						case Peer.NETWORKDATA:
							NetworkData.getInstance().noteToSelf(peer);
							break;
					}
					// Handshake failure, caller will handle disconnect
					LOGGER.debug("Peer.isOutbound - return null");
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
					LOGGER.trace("FINISHED CHALLENGE, respond CHALLENGE on {}", peer.getPeerType());
					return CHALLENGE;
				}
			}

			// Are we already connected to this peer by Challenge Type (Network / NetworkData)?
			// Use same tie-breaking logic as onHandshakeCompleted(): prefer outbound over inbound
			// But also check if existing connection appears stale/dead (half-open connection)
			switch (peer.getPeerType()) {
				case Peer.NETWORK:    // Default Network
				Peer existingNetworkPeer = Network.getInstance().getHandshakedPeerWithPublicKey(peersPublicKey);
				if (existingNetworkPeer != null && existingNetworkPeer != peer) {
					// CRITICAL: First check if existing peer is actually in connectedPeers
					// If it's in handshakedPeers but not connectedPeers, it's a ghost entry
					// Use object identity (==), not equals() which compares by address
					final Peer checkPeer = existingNetworkPeer;
					boolean isActuallyConnected = Network.getInstance().getImmutableConnectedPeers().stream()
							.anyMatch(p -> p == checkPeer);
					
					if (!isActuallyConnected) {
							// Ghost entry detected - peer is in handshakedPeers but not in connectedPeers
							// This can happen if peer was removed from connectedPeers but not from handshakedPeers
							// CRITICAL: Call disconnect() to properly clean up resources (socket, threads, etc)
							LOGGER.warn("Existing peer {} is in handshakedPeers but NOT in connectedPeers (ghost entry) - disconnecting and allowing new connection",
									existingNetworkPeer);
							existingNetworkPeer.disconnect("ghost entry - not in connectedPeers");
							// disconnect() will trigger removeConnectedPeer() which removes from both lists
							// Continue processing below to allow new connection
						} else {
							// Check if existing peer appears stale (no chain tip info after 60 seconds)
							// This handles half-open connections where one side thinks it's connected but the other doesn't
							boolean hasChainTip = (existingNetworkPeer.getChainTipData() != null);
							Long now = NTP.getTime();
							Long connTimestamp = existingNetworkPeer.getConnectionTimestamp();
							long connectionAge = (now != null && connTimestamp != null) ? (now - connTimestamp) : 0;
							boolean existingIsStale = (!hasChainTip && connectionAge > 60000);
							
							if (existingIsStale) {
								LOGGER.debug("Existing peer {} appears stale (no chain tip after {}ms), allowing replacement",
										existingNetworkPeer, connectionAge);
								// Disconnect the stale peer and allow new connection
								existingNetworkPeer.disconnect("stale connection being replaced");
								Network.getInstance().removeConnectedPeer(existingNetworkPeer);
								// Continue processing below to allow new connection
						} else {
							// Deterministic tie-breaking based on nodeId comparison
							// Both nodes will compute the same result, eliminating reconnection loops
							String ourNodeId = Network.getInstance().getOurNodeId();
							if (ourNodeId == null) {
								LOGGER.debug("Cannot determine direction (our nodeId unavailable) - keeping existing connection to {}", existingNetworkPeer);
								return null;
							}
							String theirNodeId = Crypto.toNodeAddress(peersPublicKey);
							
							// The node with the lower nodeId should be the one making outbound connections
							boolean weShouldBeOutbound = ourNodeId.compareTo(theirNodeId) < 0;
							
							// Determine which connection direction is correct
							boolean existingDirectionCorrect = (existingNetworkPeer.isOutbound() == weShouldBeOutbound);
							boolean newDirectionCorrect = (peer.isOutbound() == weShouldBeOutbound);
							
						// Check if outbound connections to this peer have been failing (reachability fallback)
						String peerIP = peer.getResolvedAddress() != null 
							? peer.getResolvedAddress().getAddress().getHostAddress() 
							: null;
						boolean outboundFailing = peerIP != null && 
							Network.getInstance().hasRecentOutboundFailures(theirNodeId, peerIP);
							
							if (existingDirectionCorrect && !outboundFailing) {
								// Existing connection has the correct direction and outbound is working - reject new
								LOGGER.debug("Duplicate detected in Network: existing {} connection is correct (ourId={}, theirId={}, weShouldBeOutbound={}), rejecting new {} connection",
										existingNetworkPeer.isOutbound() ? "outbound" : "inbound",
										ourNodeId.substring(0, 8), theirNodeId.substring(0, 8),
										weShouldBeOutbound, peer.isOutbound() ? "outbound" : "inbound");
								return null;
							} else if (existingDirectionCorrect && outboundFailing) {
								// Existing direction is "correct" but outbound keeps failing - allow inbound as fallback
								LOGGER.debug("Duplicate detected in Network: allowing {} inbound fallback for peer {} (outbound to {} is failing), replacing existing {} connection",
										peer.isOutbound() ? "outbound" : "inbound",
										theirNodeId.substring(0, 8), peerIP,
										existingNetworkPeer.isOutbound() ? "outbound" : "inbound");
								// Continue processing below - will replace existing in onHandshakeCompleted
							} else if (newDirectionCorrect) {
								// New connection has the correct direction - allow it, existing will be replaced in onHandshakeCompleted
								LOGGER.debug("Duplicate detected in Network: new {} connection is correct (ourId={}, theirId={}, weShouldBeOutbound={}), allowing to replace existing {}",
										peer.isOutbound() ? "outbound" : "inbound",
										ourNodeId.substring(0, 8), theirNodeId.substring(0, 8),
										weShouldBeOutbound, existingNetworkPeer.isOutbound() ? "outbound" : "inbound");
								// Continue processing below
							} else {
								// Neither has correct direction (shouldn't happen in normal cases)
								// Keep existing to avoid churn
								LOGGER.debug("Duplicate detected in Network: neither connection has correct direction (weShouldBeOutbound={}), keeping existing",
										weShouldBeOutbound);
								return null;
							}
						}
						}
					}
					break;
				case Peer.NETWORKDATA:  // NetworkData
					Peer existingNetworkDataPeer = NetworkData.getInstance().getHandshakedPeerWithPublicKey(peersPublicKey);
					if (existingNetworkDataPeer != null && existingNetworkDataPeer != peer) {
						// CRITICAL: First check if existing peer is actually in connectedPeers
						final Peer checkPeer = existingNetworkDataPeer;
						boolean isDataPeerActuallyConnected = NetworkData.getInstance().getImmutableConnectedPeers().stream()
								.anyMatch(p -> p == checkPeer);
						
						if (!isDataPeerActuallyConnected) {
							// Ghost entry detected - peer is in handshakedPeers but not in connectedPeers
							// This can happen if peer was removed from connectedPeers but not from handshakedPeers
							// CRITICAL: Call disconnect() to properly clean up resources (socket, threads, etc)
							LOGGER.debug("Existing NetworkData peer {} is in handshakedPeers but NOT in connectedPeers (ghost entry) - disconnecting and allowing new connection",
									existingNetworkDataPeer);
							existingNetworkDataPeer.disconnect("ghost entry - not in connectedPeers");
							// disconnect() will trigger removeConnectedPeer() which removes from both lists
							// Continue processing below
						} else {
							// Deterministic tie-breaking based on nodeId comparison
							// Both nodes will compute the same result, eliminating reconnection loops
							// Note: NetworkData doesn't use pings, so we skip stale detection and rely on tie-breaking
							String ourNodeId = NetworkData.getInstance().getOurNodeId();
							if (ourNodeId == null) {
								LOGGER.debug("Cannot determine direction (our nodeId unavailable) - keeping existing connection to {}", existingNetworkDataPeer);
								return null;
							}
							String theirNodeId = Crypto.toNodeAddress(peersPublicKey);
							
							// The node with the lower nodeId should be the one making outbound connections
							boolean weShouldBeOutbound = ourNodeId.compareTo(theirNodeId) < 0;
							
							// Determine which connection direction is correct
							boolean existingDirectionCorrect = (existingNetworkDataPeer.isOutbound() == weShouldBeOutbound);
							boolean newDirectionCorrect = (peer.isOutbound() == weShouldBeOutbound);
							
						// Check if outbound connections to this peer have been failing (reachability fallback)
						String dataPeerIP = peer.getResolvedAddress() != null 
							? peer.getResolvedAddress().getAddress().getHostAddress() 
							: null;
						boolean dataOutboundFailing = dataPeerIP != null && 
							NetworkData.getInstance().hasRecentOutboundFailures(theirNodeId, dataPeerIP);
							
							if (existingDirectionCorrect && !dataOutboundFailing) {
								// Existing connection has the correct direction and outbound is working - reject new
								LOGGER.debug("Duplicate detected in NetworkData: existing {} connection is correct (ourId={}, theirId={}, weShouldBeOutbound={}), rejecting new {} connection",
										existingNetworkDataPeer.isOutbound() ? "outbound" : "inbound",
										ourNodeId.substring(0, 8), theirNodeId.substring(0, 8),
										weShouldBeOutbound, peer.isOutbound() ? "outbound" : "inbound");
								return null;
							} else if (existingDirectionCorrect && dataOutboundFailing) {
								// Existing direction is "correct" but outbound keeps failing - allow inbound as fallback
								LOGGER.debug("Duplicate detected in NetworkData: allowing {} inbound fallback for peer {} (outbound to {} is failing), replacing existing {} connection",
										peer.isOutbound() ? "outbound" : "inbound",
										theirNodeId.substring(0, 8), dataPeerIP,
										existingNetworkDataPeer.isOutbound() ? "outbound" : "inbound");
								// Continue processing below - will replace existing in onHandshakeCompleted
							} else if (newDirectionCorrect) {
								// New connection has the correct direction - allow it, existing will be replaced in onHandshakeCompleted
								LOGGER.debug("Duplicate detected in NetworkData: new {} connection is correct (ourId={}, theirId={}, weShouldBeOutbound={}), allowing to replace existing {}",
										peer.isOutbound() ? "outbound" : "inbound",
										ourNodeId.substring(0, 8), theirNodeId.substring(0, 8),
										weShouldBeOutbound, existingNetworkDataPeer.isOutbound() ? "outbound" : "inbound");
								// Continue processing below
							} else {
								// Neither has correct direction (shouldn't happen in normal cases)
								// Keep existing to avoid churn
								LOGGER.debug("Duplicate detected in NetworkData: neither connection has correct direction (weShouldBeOutbound={}), keeping existing",
										weShouldBeOutbound);
								return null;
							}
						}
					}
					break;
			}

			peer.setPeersPublicKey(peersPublicKey);
			peer.setPeersChallenge(peersChallenge);
			LOGGER.debug("FINISHED processing CHALLENGE, start RESPONSE on {}", peer.getPeerType());
			return RESPONSE;
		}

		@Override
		public void action(Peer peer) {
			// Send challenge
			LOGGER.debug("STARTING CHALLENGE SEND - RESPONSE TO HELLO on {}", peer.getPeerType());
			// Use NetworkData's public key for NetworkData connections, Network's for Network
			byte[] publicKey;
			switch (peer.getPeerType()) {
				case Peer.NETWORKDATA:
					publicKey = NetworkData.getInstance().getOurPublicKey();
					break;
				case Peer.NETWORK:
				default:
					publicKey = Network.getInstance().getOurPublicKey();
					break;
			}
			byte[] challenge = peer.getOurChallenge();

			Message challengeMessage = new ChallengeMessage(publicKey, challenge);
			if (!peer.sendMessage(challengeMessage))
				peer.disconnect("failed to send CHALLENGE");
			LOGGER.debug("FINISHED sending CHALLENGE on {}, to {}", peer.getPeerType(), peer.getPeerData().getAddress());
		}
	},
	RESPONSE(MessageType.RESPONSE) {
		@Override
		public Handshake onMessage(Peer peer, Message message) {
			ResponseMessage responseMessage = (ResponseMessage) message;

			byte[] peersPublicKey = peer.getPeersPublicKey();
			byte[] ourChallenge = peer.getOurChallenge();

			// Defensive check: peersPublicKey can be null if the peer was rejected during CHALLENGE
			// (e.g., duplicate detection) but a RESPONSE message still arrived on the socket
			if (peersPublicKey == null) {
				LOGGER.debug("Peer {} has null public key in RESPONSE handler - likely rejected during CHALLENGE", peer);
				return null;
			}

			// Use same key as CHALLENGE: NetworkData for NetworkData connections, Network for Network
			byte[] sharedSecret;
			switch (peer.getPeerType()) {
				case Peer.NETWORKDATA:
					sharedSecret = NetworkData.getInstance().getSharedSecret(peersPublicKey);
					break;
				case Peer.NETWORK:
				default:
					sharedSecret = Network.getInstance().getSharedSecret(peersPublicKey);
					break;
			}
			final byte[] expectedData = Crypto.digest(Bytes.concat(sharedSecret, ourChallenge));

			byte[] data = responseMessage.getData();
			if (!Arrays.equals(expectedData, data)) {
				LOGGER.debug("Peer {} sent incorrect RESPONSE data", peer);
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

			/*
			 * RX side complete: We have now validated THEIR RESPONSE.
			 * Set the RX flag and try to complete the handshake.
			 * 
			 * If the TX side (PoW thread) has already sent our RESPONSE,
			 * tryCompleteHandshake() will succeed here.
			 * Otherwise, the PoW thread will complete it when it finishes.
			 * 
			 * Always return RESPONDING - completion is handled by tryCompleteHandshake().
			 */
			peer.setHandshakeResponseValidated(true);
			
			if (peer.tryCompleteHandshake()) {
				LOGGER.debug("[{}] Handshake completed by RX thread (message thread) for peer {}", 
						peer.getPeerConnectionId(), peer);
				completeHandshake(peer);
			}

			return RESPONDING;
		}

		@Override
		public void action(Peer peer) {
			// Send response

			byte[] peersPublicKey = peer.getPeersPublicKey();
			byte[] peersChallenge = peer.getPeersChallenge();

			// Defensive check: if CHALLENGE was rejected, these will be null
			if (peersPublicKey == null || peersChallenge == null) {
				LOGGER.debug("Peer {} has null public key or challenge in RESPONSE.action() - skipping", peer);
				return;
			}

			// Use same key as CHALLENGE: NetworkData for NetworkData connections, Network for Network
			byte[] sharedSecret;
			switch (peer.getPeerType()) {
				case Peer.NETWORKDATA:
					sharedSecret = NetworkData.getInstance().getSharedSecret(peersPublicKey);
					break;
				case Peer.NETWORK:
				default:
					sharedSecret = Network.getInstance().getSharedSecret(peersPublicKey);
					break;
			}
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
				if (!peer.sendMessage(responseMessage)) {
					peer.disconnect("failed to send RESPONSE");
					return;
				}

			/*
			 * TX side complete: We have successfully sent OUR RESPONSE.
			 * Set the TX flag and try to complete the handshake.
			 * 
			 * If the RX side (message thread) has already validated their RESPONSE,
			 * tryCompleteHandshake() will succeed here.
			 * Otherwise, the message thread will complete it when it validates.
			 */
			peer.setHandshakeResponseSent(true);

			if (peer.tryCompleteHandshake()) {
				LOGGER.debug("[{}] Handshake completed by TX thread (PoW thread) for peer {}", 
						peer.getPeerConnectionId(), peer);
				completeHandshake(peer);
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

	/**
	 * Centralized handshake completion logic.
	 * Called by whichever thread (RX or TX) successfully completes the handshake via tryCompleteHandshake().
	 * This ensures completion happens exactly once and in one place.
	 */
	private static void completeHandshake(Peer peer) {
		switch (peer.getPeerType()) {
			case Peer.NETWORK:
				Network.getInstance().onHandshakeCompleted(peer);
				break;
			case Peer.NETWORKDATA:
				NetworkData.getInstance().onHandshakeCompleted(peer);
				break;
		}
	}

	/** Maximum allowed difference between peer's reported timestamp and when they connected, in milliseconds. */
	private static final long MAX_TIMESTAMP_DELTA = 30 * 1000L; // ms

	private static final long PEER_VERSION_131 = 0x0100030001L;

	private static final String HELLO_V2_MIN_VERSION = "6.0.0";

	private static final int POW_BUFFER_SIZE_PRE_131 = 8 * 1024 * 1024; // bytes
	private static final int POW_DIFFICULTY_PRE_131 = 8; // leading zero bits
	// Can always be made harder in the future...
	private static final int POW_BUFFER_SIZE_POST_131 = 2 * 1024 * 1024; // bytes
	private static final int POW_DIFFICULTY_POST_131 = 2; // leading zero bits

	private static final ExecutorService responseExecutor = Executors.newFixedThreadPool(
			Settings.getInstance().getNetworkPoWComputePoolSize(),
			new DaemonThreadFactory("Network-PoW", Settings.getInstance().getHandshakeThreadPriority())
	);

	private static final byte[] ZERO_CHALLENGE = new byte[ChallengeMessage.CHALLENGE_LENGTH];

	public final MessageType expectedMessageType;

	private Handshake(MessageType expectedMessageType) {
		this.expectedMessageType = expectedMessageType;
	}

	public abstract Handshake onMessage(Peer peer, Message message);

	public abstract void action(Peer peer);

	private static boolean sendHello(Peer peer, boolean useHelloV2) {
		String versionString = Controller.getInstance().getVersionString();
		Long timestampObj = NTP.getTime();
		if (timestampObj == null) {
			LOGGER.debug("Cannot send HELLO to {} - NTP unsynchronized", peer);
			peer.disconnect("ntp unsynchronized");
			return false;
		}
		long timestamp = timestampObj;
		String senderPeerAddress = peer.getPeerData().getAddress().toString();

		Map<String, Object> capabilities = null;
		if (useHelloV2) {
			capabilities = new HashMap<>();
			if (Settings.getInstance().isQdnEnabled()) {
				capabilities.put("QDN", Settings.getInstance().getQDNListenPort());
			} else {
				capabilities.put("QDN", 0);
			}
		}

		Message helloMessage = useHelloV2
				? new HelloV2Message(timestamp, versionString, senderPeerAddress, capabilities, peer.getPeerType())
				: new HelloMessage(timestamp, versionString, senderPeerAddress);

		if (!peer.sendMessage(helloMessage)) {
			peer.disconnect("failed to send HELLO");
			return false;
		}

		return true;
	}

	private static Handshake processHelloMessage(Peer peer, HelloMessage helloMessage) {
		long peersConnectionTimestamp = helloMessage.getTimestamp();
		Long nowObj = NTP.getTime();
		if (nowObj == null) {
			LOGGER.debug("Rejecting HELLO from {} - NTP unsynchronized", peer);
			return null;
		}
		long now = nowObj;

		long timestampDelta = Math.abs(peersConnectionTimestamp - now);
		if (timestampDelta > MAX_TIMESTAMP_DELTA) {
			LOGGER.debug("Peer {} HELLO timestamp {} too divergent (± {} > {}) from ours {}",
    			peer, peersConnectionTimestamp, timestampDelta, MAX_TIMESTAMP_DELTA, now );
			return null;
		}

		switch (peer.getPeerType()) {
			case Peer.NETWORK:
				Network.getInstance().ourPeerAddressUpdated(helloMessage.getSenderPeerAddress());
				break;
			case Peer.NETWORKDATA:
				NetworkData.getInstance().ourPeerAddressUpdated(helloMessage.getSenderPeerAddress());
				break;
		}

		String versionString = helloMessage.getVersionString();

		Matcher matcher = peer.VERSION_PATTERN.matcher(versionString);
		if (!matcher.lookingAt()) {
			LOGGER.debug("Peer {} sent invalid HELLO version string '{}'", peer, versionString);
			return null;
		}

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
		peer.setPeersCapabilities(null);

		if (!Settings.getInstance().getAllowConnectionsWithOlderPeerVersions()) {
			final String minPeerVersion = Settings.getInstance().getMinPeerVersion();
			if (!peer.isAtLeastVersion(minPeerVersion)) {
				LOGGER.debug("Ignoring peer {} because it is on an old version ({}})", peer, versionString);
				return null;
			}
		}

		if (peer.isAtLeastVersion(HELLO_V2_MIN_VERSION) && !peer.isOutbound()) {
			peer.setAwaitingHelloV2Response(true);
			return HELLO_V2;
		}

		LOGGER.debug("INBOUND - FINISHED PROCESSING HELLO, ready for CHALLENGE on {}", peer.getPeerType());
		return CHALLENGE;
	}

	private static Handshake processHelloV2Message(Peer peer, HelloV2Message helloMessage) {
		long peersConnectionTimestamp = helloMessage.getTimestamp();
		Long nowObj = NTP.getTime();
		if (nowObj == null) {
			LOGGER.debug("Rejecting HELLO_V2 from {} - NTP unsynchronized", peer);
			return null;
		}
		long now = nowObj;

		long timestampDelta = Math.abs(peersConnectionTimestamp - now);
		if (timestampDelta > MAX_TIMESTAMP_DELTA) {
			LOGGER.debug("Peer {} HELLO timestamp {} too divergent (± {} > {}) from ours {}",
					peer, peersConnectionTimestamp, timestampDelta, MAX_TIMESTAMP_DELTA, now);
			return null;
		}

	int peerType = helloMessage.getPeerType();
	if (peerType != Peer.NETWORK && peerType != Peer.NETWORKDATA) {
			LOGGER.debug("Peer {} sent invalid peerType {} in HELLO_V2", peer, peerType);
			return null;
		}
		peer.setPeerType(peerType);

		switch (peerType) {
			case Peer.NETWORK:
				Network.getInstance().ourPeerAddressUpdated(helloMessage.getSenderPeerAddress());
				break;
			case Peer.NETWORKDATA:
				NetworkData.getInstance().ourPeerAddressUpdated(helloMessage.getSenderPeerAddress());
				break;
		}

		String versionString = helloMessage.getVersionString();

		Matcher matcher = peer.VERSION_PATTERN.matcher(versionString);
		if (!matcher.lookingAt()) {
			LOGGER.debug("Peer {} sent invalid HELLO version string '{}'", peer, versionString);
			return null;
		}

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
		peer.setPeersCapabilities(helloMessage.getCapabilities());
		peer.setAwaitingHelloV2Response(false);

		if (!Settings.getInstance().getAllowConnectionsWithOlderPeerVersions()) {
			final String minPeerVersion = Settings.getInstance().getMinPeerVersion();
			if (!peer.isAtLeastVersion(minPeerVersion)) {
				LOGGER.debug("Ignoring peer {} because it is on an old version ({})", peer, versionString);
				return null;
			}
		}

		if (peer.isOutbound()) {
			if (!sendHello(peer, true)) {
				return null;
			}
		}

		return CHALLENGE;
	}
}
