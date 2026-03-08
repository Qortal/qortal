package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.List;

/**
 * Subject Alternative Names from the current SSL server certificate.
 * Lists the DNS names and IP addresses the certificate is valid for.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class CertificateSanInfo {

	public final List<String> dns;
	public final List<String> ip;

	/** Default constructor for JAXB/MOXy serialization. */
	public CertificateSanInfo() {
		this.dns = new ArrayList<>();
		this.ip = new ArrayList<>();
	}

	public CertificateSanInfo(List<String> dns, List<String> ip) {
		this.dns = dns;
		this.ip = ip;
	}
}
