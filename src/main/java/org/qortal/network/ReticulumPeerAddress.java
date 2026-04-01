package org.qortal.network;

//import lombok.Data;
//import lombok.extern.slf4j.Slf4j;
//import org.qortal.settings.Settings;

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

    }
