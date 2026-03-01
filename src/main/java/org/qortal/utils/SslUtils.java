package org.qortal.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.bouncycastle.util.io.pem.PemWriter;
import org.qortal.settings.Settings;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Date;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SslUtils {

    private static final Logger LOGGER = LogManager.getLogger(SslUtils.class);

    private static final String CA_CERT_PATH = "ca.crt";
    private static final String CA_KEY_PATH = "ca.key";
    private static final String SERVER_CERT_PATH = "server.crt";
    private static final String SERVER_KEY_PATH = "server.key";

    static {
        Security.addProvider(new BouncyCastleProvider());
        Security.addProvider(new org.bouncycastle.jsse.provider.BouncyCastleJsseProvider());
    }

    /** Returns true if the string is a literal IP address (IPv4 or IPv6), so it must not be used as a DNS name in SAN. */
    private static boolean isIpAddress(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        try {
            return InetAddress.getByName(s).getHostAddress().equals(s);
        } catch (Exception e) {
            return false;
        }
    }

    public static void generateSsl() {
        try {
            // Generate key pair
            KeyPairGenerator kpGen = KeyPairGenerator.getInstance("RSA", "BC");
            kpGen.initialize(2048, new SecureRandom());
            KeyPair keyPair = kpGen.generateKeyPair();

            // Create self-signed certificate
            X500Name subject = new X500Name("CN=qortal.org");
            BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
            Date notBefore = new Date();
            Date notAfter = new Date(System.currentTimeMillis() + (10L * 365 * 24 * 60 * 60 * 1000));

            JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, keyPair.getPublic());
            certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

            JcaContentSignerBuilder signerBuilder = new JcaContentSignerBuilder("SHA256WithRSAEncryption").setProvider("BC");
            X509Certificate cert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(certBuilder.build(signerBuilder.build(keyPair.getPrivate())));

            // Save CA certificate and key
            saveCert(cert, CA_CERT_PATH);
            saveKey(keyPair.getPrivate(), CA_KEY_PATH);

            // Generate server certificate signed by the CA
            createServerCertificate(keyPair);

            // Create keystore
            createKeystore();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate SSL certificates", e);
        }
    }

    private static void createServerCertificate(KeyPair caKeyPair) throws Exception {
        // Generate server key pair
        KeyPairGenerator kpGen = KeyPairGenerator.getInstance("RSA", "BC");
        kpGen.initialize(2048, new SecureRandom());
        KeyPair serverKeyPair = kpGen.generateKeyPair();

        // Create server certificate
        X500Name subject = new X500Name("CN=qortal-server"); // Standardize the CN
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + (365 * 24 * 60 * 60 * 1000));

        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                new X500Name("CN=qortal.org"),
                serial,
                notBefore,
                notAfter,
                subject,
                serverKeyPair.getPublic());

        certBuilder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
        certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

        // --- START SAN EXTENSION ADDITION ---
        List<GeneralName> altNames = new ArrayList<>();

        // DNS Entries
        altNames.add(new GeneralName(GeneralName.dNSName, "localhost"));

        InetAddress localHost = null;
        try {
            localHost = InetAddress.getLocalHost();
            String hostName = localHost.getHostName(); // Shortname
            String canonicalHostName = localHost.getCanonicalHostName(); // FQDN

         
            if (!isIpAddress(hostName)) {
                altNames.add(new GeneralName(GeneralName.dNSName, hostName));
            }
            if (!canonicalHostName.equalsIgnoreCase(hostName) && !isIpAddress(canonicalHostName)) {
                altNames.add(new GeneralName(GeneralName.dNSName, canonicalHostName));
            }
        } catch (Exception e) {
            LOGGER.warn("Could not resolve local hostname for SSL certificate SAN, using localhost only: {}", e.getMessage());
        }

        // IP Entries
        // Always add loopback explicitly
        altNames.add(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
        
        // Discover and add all local network interface IPs
        Set<String> addedIps = new HashSet<>();
        addedIps.add("127.0.0.1"); // Track loopback to avoid duplicates
        
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                
                // Skip interfaces that are down or loopback (we already added 127.0.0.1)
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    
                    // Skip loopback addresses (redundant check for safety)
                    if (address.isLoopbackAddress()) {
                        continue;
                    }
                    
                    // Skip link-local addresses (169.254.x.x for IPv4, fe80:: for IPv6)
                    if (address.isLinkLocalAddress()) {
                        continue;
                    }
                    
                    String ipAddress = address.getHostAddress();
                    
                    // For IPv6 addresses, strip zone identifier if present (e.g., %eth0)
                    int percentIndex = ipAddress.indexOf('%');
                    if (percentIndex > 0) {
                        ipAddress = ipAddress.substring(0, percentIndex);
                    }
                    
                    // Avoid duplicate IPs
                    if (!addedIps.contains(ipAddress)) {
                        altNames.add(new GeneralName(GeneralName.iPAddress, ipAddress));
                        addedIps.add(ipAddress);
                        LOGGER.info("Adding IP address to SSL certificate SAN: {}", ipAddress);
                    }
                }
            }
        } catch (SocketException e) {
            LOGGER.warn("Failed to enumerate network interfaces for SSL certificate: {}", e.getMessage());
            // Fallback to old behavior if network enumeration fails
            if (localHost != null) {
                try {
                    String fallbackIp = localHost.getHostAddress();
                    if (!addedIps.contains(fallbackIp)) {
                        altNames.add(new GeneralName(GeneralName.iPAddress, fallbackIp));
                        addedIps.add(fallbackIp);
                        LOGGER.info("Added fallback IP address to SSL certificate SAN: {}", fallbackIp);
                    }
                } catch (Exception ex) {
                    LOGGER.warn("Failed to get fallback IP address: {}", ex.getMessage());
                }
            }
        }
        
        LOGGER.info("SSL certificate will be valid for {} IP address(es) and {} DNS name(s)", 
                addedIps.size(), altNames.size() - addedIps.size());

        GeneralNames subjectAltNames = new GeneralNames(altNames.toArray(new GeneralName[0]));
        certBuilder.addExtension(Extension.subjectAlternativeName, false, subjectAltNames);
        // --- END SAN EXTENSION ADDITION ---

        JcaContentSignerBuilder signerBuilder = new JcaContentSignerBuilder("SHA256WithRSAEncryption").setProvider("BC");
        X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(certBuilder.build(signerBuilder.build(caKeyPair.getPrivate())));

        // Save server certificate and key
        saveCert(cert, SERVER_CERT_PATH);
        saveKey(serverKeyPair.getPrivate(), SERVER_KEY_PATH);
    }
    private static void saveCert(X509Certificate cert, String path) throws Exception {
        StringWriter sw = new StringWriter();
        try (PemWriter pw = new PemWriter(sw)) {
            pw.writeObject(new PemObject("CERTIFICATE", cert.getEncoded()));
        }
        try (FileOutputStream fos = new FileOutputStream(path)) {
            fos.write(sw.toString().getBytes());
        }
        setFilePermissions(path);
    }

    private static void saveKey(PrivateKey key, String path) throws Exception {
        StringWriter sw = new StringWriter();
        try (PemWriter pw = new PemWriter(sw)) {
            pw.writeObject(new PemObject("PRIVATE KEY", key.getEncoded()));
        }
        try (FileOutputStream fos = new FileOutputStream(path)) {
            fos.write(sw.toString().getBytes());
        }
        setFilePermissions(path);
    }

    private static void setFilePermissions(String path) {
        File file = new File(path);
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
    }

    private static void createKeystore() throws Exception {
        // Load CA certificate
        CertificateFactory cf = CertificateFactory.getInstance("X.509", "BC");
        X509Certificate caCert = (X509Certificate) cf.generateCertificate(new FileInputStream(CA_CERT_PATH));

        // Load server certificate
        X509Certificate serverCert = (X509Certificate) cf.generateCertificate(new FileInputStream(SERVER_CERT_PATH));

        // Load server key
        PrivateKey serverKey;
        try (FileReader keyReader = new FileReader(SERVER_KEY_PATH); PemReader pemReader = new PemReader(keyReader)) {
            PemObject pemObject = pemReader.readPemObject();
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(pemObject.getContent());
            KeyFactory kf = KeyFactory.getInstance("RSA", "BC");
            serverKey = kf.generatePrivate(keySpec);
        }

        // Create and save keystore using the default (SunJSSE) PKCS12 provider, not BC.
        // When BouncyCastle is repackaged into an uber jar it becomes unsigned, and on Oracle
        // Java SE the JCE refuses to use BC for key encryption ("JCE cannot authenticate the
        // provider BC") during keyStore.store(). The default provider avoids that; the resulting
        // PKCS12 file is standard and is read by Jetty (with BC) without issue.
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);

        // Add server certificate and key (single key entry with chain — supported by default PKCS12).
        keyStore.setKeyEntry("server", serverKey, Settings.getInstance().getSslKeystorePassword().toCharArray(), new java.security.cert.Certificate[]{serverCert, caCert});

        // Save keystore (encryption performed by default provider; no BC authentication required).
        try (FileOutputStream fos = new FileOutputStream(Settings.getInstance().getSslKeystorePathname())) {
            keyStore.store(fos, Settings.getInstance().getSslKeystorePassword().toCharArray());
        }
    }
}
