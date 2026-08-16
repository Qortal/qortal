package org.qortal.network.reticulum;

import io.reticulum.identity.Identity;
import io.reticulum.transport.AnnounceHandler;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;

import org.qortal.settings.Settings;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static io.reticulum.link.LinkStatus.ACTIVE;
import static io.reticulum.link.LinkStatus.CLOSED;
import static io.reticulum.utils.DestinationUtils.hashFromNameAndIdentity;
import static java.util.Objects.nonNull;
import static org.apache.commons.codec.binary.Hex.encodeHexString;

/**
 * Receives announces for one aspect and turns the useful ones into initiator peers.
 * <p>
 * One instance per aspect ("qortal.core", "qortal.qdn"), each registered with {@code Transport} in
 * {@code RNS.start()}. Everything that differs between the two is a constructor argument.
 * <p>
 * The three things an announce can carry us: a peer worth connecting to (below), a gateway worth
 * dialling (handed to {@link RNSGatewayManager}), and the peer's version (cached in
 * {@link AnnouncedVersionCache} for the inbound path, which has no announce of its own). Decoding
 * the payload is {@link RNSAnnounceCodec}'s job; this class only decides what to do with it.
 */
@Slf4j
final class RNSAnnounceHandler implements AnnounceHandler {

    final String aspectFilter;
    private final RNSCommon.PeerAspect aspect;
    private final int minDesiredPeers;
    private final RNSPeerRegistry registry;
    private final RNSGatewayManager gateways;
    private final AnnouncedVersionCache versions;
    private final byte[] messageMagic;
    private final Consumer<ReticulumPeer> addLinkedPeer;

    RNSAnnounceHandler(String aspectFilter, RNSCommon.PeerAspect aspect, int minDesiredPeers,
                       RNSPeerRegistry registry, RNSGatewayManager gateways,
                       AnnouncedVersionCache versions, byte[] messageMagic,
                       Consumer<ReticulumPeer> addLinkedPeer) {
        this.aspectFilter = aspectFilter;
        this.aspect = aspect;
        this.minDesiredPeers = minDesiredPeers;
        this.registry = registry;
        this.gateways = gateways;
        this.versions = versions;
        this.messageMagic = messageMagic;
        this.addLinkedPeer = addLinkedPeer;
    }

    @Override
    public String getAspectFilter() {
        // Return null so Transport fires this handler for ALL received announces.
        // Transport's hash-based filter (hashFromNameAndIdentity(aspectFilter, recall(hash)))
        // fails whenever recall() returns null for the incoming announce identity — the
        // computed hash (no identity component) never matches the actual destination hash,
        // so receivedAnnounce() is never called. We filter by name inside the handler instead.
        return null;
    }

    @Override
    // Serialises announce processing per handler instance. Note the two instances (BASE, DATA)
    // hold separate Lombok $locks, so this does NOT serialise the aspects against each other —
    // it only stops one aspect's announces from interleaving with themselves. Cheap now that
    // the gateway dial (a TCP connect) runs on RNSGatewayManager's executor rather than here,
    // on Reticulum's announce-delivery thread.
    @Synchronized
    public void receivedAnnounce(byte[] destinationHash,
                                 Identity announcedIdentity,
                                 byte[] appData,
                                 byte[] announcePacketHash,
                                 boolean isPathResponse) {
        var peerExists = false;
        var activePeerCount = 0;

        log.debug("Received an announce from {}", encodeHexString(destinationHash));

        // Since getAspectFilter() returns null (match-all), we must verify manually.
        // Recompute the expected hash for "qortal.core" + the announced identity and
        // compare; skip announces that belong to other apps/aspects.
        var expectedHash = hashFromNameAndIdentity(this.aspectFilter, announcedIdentity);
        if (!Arrays.equals(destinationHash, expectedHash)) {
            log.debug("Announce hash mismatch — identity={}, dest={}, expected={}",
                    announcedIdentity != null ? encodeHexString(announcedIdentity.getHash()) : "null",
                    encodeHexString(destinationHash),
                    encodeHexString(expectedHash));
            return;
        }

        String announcedVersion = null;
        if (nonNull(appData)) {
            RNSAnnounceCodec.AnnounceInfo info = RNSAnnounceCodec.decode(appData);
            announcedVersion = info.getVersion();
            // If the announce advertises a Qortal gateway, optionally dial it as a dynamic
            // backbone client interface.
            if (info.hasGateway()) {
                gateways.maybeAddDynamicGateway(info.getGatewayHost(), info.getGatewayPort());
            }
            // Cache the announced version so incoming peers (no announce at construction) can
            // resolve it once they identify.
            if (announcedVersion != null) {
                versions.put(announcedIdentity, announcedVersion);
            }
        }

        // Enforce minPeerVersion: skip peers that announce a version below the configured
        // minimum (unless the operator allows older peers). Unknown/unparseable versions are
        // NOT skipped — only a known, parseable, below-minimum version is rejected.
        if (announcedVersion != null && !Settings.getInstance().getAllowConnectionsWithOlderPeerVersions()) {
            long announced = RNSAnnounceCodec.parseVersionToLong(announcedVersion);
            long minVersion = RNSAnnounceCodec.parseVersionToLong(Settings.getInstance().getMinPeerVersion());
            if (announced != 0 && minVersion != 0 && announced < minVersion) {
                log.info("Skipping announce from {} — version {} < minPeerVersion {}",
                        encodeHexString(destinationHash), announcedVersion,
                        Settings.getInstance().getMinPeerVersion());
                return;
            }
        }

        // add to peer list if we can use more peers
        List<ReticulumPeer> lps = registry.linked();
        for (ReticulumPeer p: lps) {
            var pl = p.getPeerLink();
            if (nonNull(pl) && pl.getStatus() == ACTIVE && p.getPeerAspect() == this.aspect) {
                activePeerCount = activePeerCount + 1;
            }
        }
        if (activePeerCount < this.minDesiredPeers) {
            for (ReticulumPeer p: lps) {
                if (Arrays.equals(p.getDestinationHash(), destinationHash)) {
                    // DEBUG, not INFO: this whole loop runs per received announce, and every
                    // peer on the mesh announces every ~30s. Only the "added new peer" line
                    // below is a state change worth an INFO.
                    log.debug("QAnnounceHandler - peer exists - found peer matching destinationHash");
                    if (nonNull(p.getPeerLink())) {
                        log.debug("peer link: {}, status: {}",
                                encodeHexString(p.getPeerLink().getLinkId()), p.getPeerLink().getStatus());
                    }
                    peerExists = true;
                    if (nonNull(p.getPeerLink()) && (p.getPeerLink().getStatus() == CLOSED)) {
                        // Only re-initiate for CLOSED links. PENDING links are already
                        // connecting — creating a second link would race with the first
                        // and the first's TIMEOUT callback would set peerTimedOut=true,
                        // poisoning the peer and triggering premature pruning.
                        p.getOrInitPeerLink();
                    }
                    break;
                } else {
                    if (nonNull(p.getPeerLink())) {
                        log.debug("QAnnounceHandler - other peer - link: {}, status: {}",
                                encodeHexString(p.getPeerLink().getLinkId()), p.getPeerLink().getStatus());
                        if (p.getPeerLink().getStatus() == CLOSED) {
                            // mark peer for deletion on next pruning
                            p.setDeleteMe(true);
                        }
                    } else {
                        log.debug("QAnnounceHandler - peer link is null");
                    }
                }
            }
            if (!peerExists) {
                ReticulumPeer newPeer = getNewPeer(destinationHash, announcedIdentity, announcedVersion);
                addLinkedPeer.accept(newPeer);
                log.info("added new {} ReticulumPeer, destinationHash: {}, version: {}",
                        newPeer.getPeerAspect(), encodeHexString(destinationHash), announcedVersion);
            }
        }
    }

    private ReticulumPeer getNewPeer(byte[] destinationHash, Identity announcedIdentity, String announcedVersion) {
        // Aspect is set by the constructor; setIsDataPeer() is only a setPeerAspect() wrapper.
        ReticulumPeer newPeer = new ReticulumPeer(destinationHash, this.aspect);
        newPeer.setServerIdentity(announcedIdentity);
        newPeer.setIsInitiator(true);
        newPeer.setMessageMagic(this.messageMagic);
        // Version advertised in the announce appData (may be null if not present); surfaced via
        // /peers/reticulum. Display-only — the numeric min-version gate is unaffected.
        if (announcedVersion != null) {
            newPeer.setPeersVersionString(announcedVersion);
        }
        log.debug(">>> ReticulumPeer created - PeerData: {} - {}", newPeer.getPeerData().toString(), newPeer.getPeerAddress().getDestinationHash());
        return newPeer;
    }
}
