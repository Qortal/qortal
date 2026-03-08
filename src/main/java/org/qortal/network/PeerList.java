package org.qortal.network;

import org.qortal.data.network.PeerData;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An immutable, thread-safe snapshot of a list of Peers.
 * <p>
 * Provides fast, map-based lookups for
 * getting or checking containment of a Peer by its PeerData or PeerAddress.
 * <p>
 * The {@code get(...)} and {@code contains(...)} methods
 * match based **only on the host/IP address**, ignoring the port.
 * <p>
 * It also provides common list/collection methods like stream(), iterator(),
 * size(), and isEmpty() so it can be used as a drop-in replacement for
 * a {@code List<Peer>} where iteration is needed.
 */
public class PeerList implements Iterable<Peer> {

    private final List<Peer> peerList;
    private final Map<String, Peer> peerMap; // Key is the case-insensitive host string

    /**
     * Creates an empty PeerList.
     */
    public PeerList() {
        this.peerList = Collections.emptyList();
        this.peerMap = Collections.emptyMap();
    }

    /**
     * Creates a new immutable PeerList by taking a snapshot
     * of the provided collection of peers.
     *
     * @param sourcePeers The collection of peers to copy.
     */
    public PeerList(Collection<Peer> sourcePeers) {
        // Create an immutable list copy
        this.peerList = List.copyOf(sourcePeers);

        // Build a map for fast lookups based on the peer's host (case-insensitive)
        this.peerMap = this.peerList.stream()
                .collect(Collectors.toMap(
                        // Use the case-insensitive host as the key
                        peer -> peer.getPeerData().getAddress().getHost().toLowerCase(),
                        Function.identity(),
                        (existing, replacement) -> existing // Handle duplicates, keep existing
                ));
    }

    /**
     * Returns the Peer associated with the given PeerData,
     * by looking up its host/IP address (ignores port).
     *
     * @param pd The PeerData to search for.
     * @return The matching Peer, or null if not found.
     */
    public Peer get(PeerData pd) {
        if (pd == null || pd.getAddress() == null) {
            return null;
        }
        // Get host and lookup using the case-insensitive key
        final String host = pd.getAddress().getHost().toLowerCase();
        return peerMap.get(host);
    }

    /**
     * Returns the Peer associated with the given PeerAddress,
     * by looking up its host/IP address (ignores port).
     *
     * @param pa The PeerAddress to search for.
     * @return The matching Peer, or null if not found.
     */
    public Peer get(PeerAddress pa) {
        if (pa == null) {
            return null;
        }
        // Get host and lookup using the case-insensitive key
        final String host = pa.getHost().toLowerCase();
        return peerMap.get(host);
    }

    /**
     * Checks if a Peer associated with the given PeerData
     * (based on its host/IP address, ignoring port) exists in this list.
     *
     * @param pd The PeerData to check for.
     * @return true if a matching Peer is in the list, false otherwise.
     */
    public boolean contains(PeerData pd) {
        if (pd == null || pd.getAddress() == null) {
            return false;
        }
        // Get host and check using the case-insensitive key
        final String host = pd.getAddress().getHost().toLowerCase();
        return peerMap.containsKey(host);
    }

    /**
     * Checks if a Peer associated with the given PeerAddress
     * (based on its host/IP address, ignoring port) exists in this list.
     *
     * @param pa The PeerAddress to check for.
     * @return true if a matching Peer is in the list, false otherwise.
     */
    public boolean contains(PeerAddress pa) {
        if (pa == null) {
            return false;
        }
        // Get host and check using the case-insensitive key
        final String host = pa.getHost().toLowerCase();
        return peerMap.containsKey(host);
    }

    /**
     * Returns an iterator over the peers in this list.
     *
     * @return an Iterator.
     */
    @Override
    public Iterator<Peer> iterator() {
        return peerList.iterator();
    }

    /**
     * Returns a sequential Stream with this list as its source.
     *
     * @return a sequential Stream over the peers in this list.
     */
    public Stream<Peer> stream() {
        return peerList.stream();
    }

    /**
     * Returns the number of peers in this list.
     *
     * @return the number of peers.
     */
    public int size() {
        return peerList.size();
    }

    /**
     * Returns true if this list contains no peers.
     *
     * @return true if the list is empty.
     */
    public boolean isEmpty() {
        return peerList.isEmpty();
    }
}