package org.qortal.network.reticulum;

//import lombok.Data;
//import lombok.extern.slf4j.Slf4j;
//import org.qortal.settings.Settings;

import org.qortal.network.PeerAddress;
import org.qortal.network.PeerAddressCtor;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.net.InetSocketAddress;
import static org.apache.commons.codec.binary.Hex.encodeHexString;

/**
 * Convenience class for encapsulating/parsing/rendering/converting IP peer addresses
 * including late-stage resolving before actual use by a socket.
 */
// All properties to be converted to JSON via JAXB
//@Data
//@Slf4j
@XmlAccessorType(XmlAccessType.FIELD)
public class ReticulumPeerAddress implements PeerAddress {

    byte[] dhash;

	  // Constructors

	  // For JAXB
	  protected ReticulumPeerAddress() {
	  }

    @PeerAddressCtor("destination-hash")
	  public ReticulumPeerAddress(byte[] dhash) {
        this.dhash = dhash;
    }

    @Override
    public byte[] getDestinationHash() { return dhash; }

    public static ReticulumPeerAddress fromString(String addressString) throws IllegalArgumentException {
        return null;
    }

    @Override
    public InetSocketAddress toSocketAddress() {
        return null;
    }
    @Override
    public String toString() { return encodeHexString(dhash); }

    /**
     * Two Reticulum addresses are the same peer iff they carry the same destination hash.
     * <p>
     * As with {@link IPPeerAddress#equals(Object)} this has to override {@link Object#equals(Object)}:
     * callers only ever hold a {@link PeerAddress}, so an overload would never be selected.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;

        if (!(other instanceof ReticulumPeerAddress))
            return false;

        return java.util.Arrays.equals(this.dhash, ((ReticulumPeerAddress) other).dhash);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(this.dhash);
    }

    }
