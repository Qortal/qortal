package org.qortal.network;

import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;
import org.qortal.settings.Settings;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.net.*;
import java.util.Locale;
import java.util.Optional;

/**
 * Convenience class for encapsulating/parsing/rendering/converting IP peer addresses
 * including late-stage resolving before actual use by a socket.
 */
// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class IPPeerAddress implements PeerAddress {

	// Properties
	private String host;
	private int port;

    @PeerAddressCtor("host-port")
	private IPPeerAddress(String host, int port) {
		this.host = host;
		this.port = port;
	}

    @PeerAddressCtor("uri")
    public IPPeerAddress(String uri) {
        if (uri == null || uri.trim().isEmpty()) {
            throw new IllegalArgumentException("Peer URI cannot be null or empty.");
        }

        String trimmedUri = uri.trim();

        // Split the URI string at the first colon, resulting in at most two parts.
        // This is generally safe for host:port separation.
        String[] parts = trimmedUri.split(":", 2);
        if (parts.length > 2) {
            throw new IllegalArgumentException("Peer URI is not formated correctly");
        }

        if (parts.length == 2) {
            // Format is "host:port"
            this.host = parts[0].trim();

            try {
                // Attempt to parse the port part as an integer
                this.port = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                // Throw an exception if the port number is malformed (e.g., "host:abc")
                throw new IllegalArgumentException("Invalid port number in Peer URI: " + trimmedUri);
            }
        } else {
            // Format is "host" (no colon found)
            this.host = trimmedUri;
            this.port = 0; // Indicate that the port is missing/not specified
        }
    }

	// Constructors

	// For JAXB
	protected IPPeerAddress() {
	}

	/** Constructs new IPPeerAddress using remote address from passed connected socket. */
	public static IPPeerAddress fromSocket(Socket socket) {
		InetSocketAddress socketAddress = (InetSocketAddress) socket.getRemoteSocketAddress();
		InetAddress address = socketAddress.getAddress();

		String host = InetAddresses.toAddrString(address);

		// Make sure we encapsulate IPv6 addresses in brackets
		if (address instanceof Inet6Address)
			host = "[" + host + "]";

		return new IPPeerAddress(host, socketAddress.getPort());
	}

	/**
	 * Constructs new IPPeerAddress using hostname or literal IP address and optional port.<br>
	 * Literal IPv6 addresses must be enclosed within square brackets.
	 * <p>
	 * Examples:
	 * <ul>
	 * <li>peer.example.com
	 * <li>peer.example.com:9084
	 * <li>192.0.2.1
	 * <li>192.0.2.1:9084
	 * <li>[2001:db8::1]
	 * <li>[2001:db8::1]:9084
	 * </ul>
	 * <p>
	 * Not allowed:
	 * <ul>
	 * <li>2001:db8::1
	 * <li>2001:db8::1:9084
	 * </ul>
	 */
	public static IPPeerAddress fromString(String addressString) throws IllegalArgumentException {
		boolean isBracketed = addressString.startsWith("[");

		// Attempt to parse string into host and port
		HostAndPort hostAndPort = HostAndPort.fromString(addressString).withDefaultPort(Settings.getInstance().getDefaultListenPort()).requireBracketsForIPv6();

		String host = hostAndPort.getHost();
		if (host.isEmpty())
			throw new IllegalArgumentException("Empty host part");

		// Validate IP literals by attempting to convert to InetAddress, without DNS lookups
		if (host.contains(":") || host.matches("[0-9.]+"))
			InetAddresses.forString(host);

		// If we've reached this far then we have a valid address

		// Make sure we encapsulate IPv6 addresses in brackets
		if (isBracketed)
			host = "[" + host + "]";

		return new IPPeerAddress(host, hostAndPort.getPort());
	}

	// Getters

	/** Returns hostname or literal IP address, bracketed if IPv6 */
	public String getHost() {
		return this.host;
	}

	public int getPort() {
		return this.port;
	}

	// Conversions

	/** Returns InetSocketAddress for use with Socket.connect(), or throws UnknownHostException if address could not be resolved by DNS lookup. */
	public InetSocketAddress toSocketAddress() throws UnknownHostException {
		// Attempt to construct new InetSocketAddress with DNS lookups.
		// There's no control here over whether IPv6 or IPv4 will be used.
		InetSocketAddress socketAddress = new InetSocketAddress(this.host, this.port);

		// If we couldn't resolve then return null
		if (socketAddress.isUnresolved())
			throw new UnknownHostException();

		return socketAddress;
	}

	@Override
	public String toString() {
		return this.host + ":" + this.port;
	}

	// Utilities

	/**
	 * Returns true if other is an IPPeerAddress with same port and same case-insensitive host part,
	 * without DNS lookups.
	 * <p>
	 * This MUST override {@link Object#equals(Object)} rather than being an {@code equals(IPPeerAddress)}
	 * overload. Every caller in the codebase holds these through the {@link PeerAddress} interface
	 * (e.g. {@code PeerData.getAddress()}), so an overload is never selected — the compiler binds to
	 * {@code Object.equals} and silently compares by reference. That is how peer de-duplication in
	 * Network.mergePeersUnlocked stopped matching anything, letting allKnownPeers grow without bound.
	 */
	@Override
	public boolean equals(Object other) {
		if (this == other)
			return true;

		if (!(other instanceof IPPeerAddress))
			return false;

		IPPeerAddress otherAddress = (IPPeerAddress) other;

		// Ports must match
		if (this.port != otherAddress.port)
			return false;

		// Compare host parts but without DNS lookups
		if (this.host == null || otherAddress.host == null)
			return this.host == otherAddress.host;

		return this.host.equalsIgnoreCase(otherAddress.host);
	}

	/** Must agree with {@link #equals(Object)}: case-insensitive over host, so lower-case before hashing. */
	@Override
	public int hashCode() {
		return 31 * (this.host == null ? 0 : this.host.toLowerCase(Locale.ROOT).hashCode()) + this.port;
	}

}
