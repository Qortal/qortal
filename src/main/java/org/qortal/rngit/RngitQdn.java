package org.qortal.rngit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataReader;
import org.qortal.arbitrary.ArbitraryDataTransactionBuilder;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.misc.Service;
import org.qortal.block.BlockChain;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.transaction.Transaction;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Git repositories on QDN: the resource layout and the reads and publishes
 * against it.
 * <p>
 * A repository {@code <repo>} under Qortal name {@code <name>} is, in service
 * {@code GIT_REPOSITORY}:
 * <ul>
 *   <li>a descriptor, identifier {@code <repo>}: a directory holding
 *       {@value #DESCRIPTOR_FILE} ({@link Descriptor}), replaced on every update;</li>
 *   <li>bundles, identifier {@code <repo>~b~<n>}: a directory holding
 *       {@value #BUNDLE_FILE}, a git bundle whose prerequisites are in earlier
 *       bundles; published once and never changed.</li>
 * </ul>
 * QDN itself authenticates both: a resource under a name can only be published
 * by the account owning that name, and its data is checked against the hash in
 * the signed transaction.
 */
@Slf4j
public final class RngitQdn {

    public static final Service SERVICE = Service.GIT_REPOSITORY;
    public static final String DESCRIPTOR_FILE = "rngit.json";
    public static final String BUNDLE_FILE = "bundle";
    public static final String FORMAT = "rngit-qdn/1";

    /** QDN identifiers are at most 64 characters; leave room for {@code ~b~<n>}. */
    public static final int MAX_REPOSITORY_NAME = 48;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The repository descriptor, as stored in {@value #DESCRIPTOR_FILE}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Descriptor {
        public String format = FORMAT;
        public String repository;
        /** The branch HEAD points to, e.g. {@code refs/heads/main}. */
        public String head;
        /** Ref name to SHA; authoritative for the repository's refs. */
        public Map<String, String> refs = new LinkedHashMap<>();
        /** Bundle identifiers, applied in order to reconstruct the objects. */
        public List<String> bundles = new ArrayList<>();
        /** The repository's rules, in {@code .allowed} syntax. */
        public String allowed;
        /** {@code fork} or {@code mirror}, with its upstream URL, else null. */
        public String type;
        public String upstream;
        public String description;
    }

    private RngitQdn() {
    }

    public static String bundleIdentifier(String repository, long number) {
        return repository + "~b~" + number;
    }

    /** A repository name usable as a QDN identifier, with room for bundle suffixes. */
    public static boolean isValidRepositoryName(String repository) {
        return repository != null && !repository.isEmpty() && repository.length() <= MAX_REPOSITORY_NAME
                && !repository.contains("~") && !repository.contains("/") && !repository.equals(".") && !repository.equals("..");
    }

    /**
     * The file a resource holds: {@code preferred} when present, otherwise its
     * only file. Resources published as a single base64 file (as Q-Apps do
     * through Hub) keep whatever filename the publisher gave, or none.
     */
    public static Path fileIn(Path resource, String preferred) throws IOException {
        if (Files.isRegularFile(resource)) return resource;
        Path named = resource.resolve(preferred);
        if (Files.isRegularFile(named)) return named;
        try (var files = Files.list(resource)) {
            List<Path> found = new ArrayList<>();
            files.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().startsWith(".")).forEach(found::add);
            if (found.size() == 1) return found.get(0);
        }
        throw new IOException("Resource " + resource + " holds no " + preferred);
    }

    public static Descriptor parseDescriptor(Path directory) throws IOException {
        Descriptor descriptor = JSON.readValue(fileIn(directory, DESCRIPTOR_FILE).toFile(), Descriptor.class);
        if (!FORMAT.equals(descriptor.format)) {
            throw new IOException("Unsupported descriptor format " + descriptor.format);
        }
        return descriptor;
    }

    public static byte[] descriptorBytes(Descriptor descriptor) throws IOException {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(descriptor);
    }

    public static void writeDescriptor(Descriptor descriptor, Path directory) throws IOException {
        Files.createDirectories(directory);
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(DESCRIPTOR_FILE).toFile(), descriptor);
    }

    // ------------------------------------------------------------------
    // Chain lookups

    /** The current owner's address of a registered name, or null. */
    public static String nameOwner(String name) {
        try (Repository repository = RepositoryManager.getRepository()) {
            NameData nameData = repository.getNameRepository().fromName(name);
            return nameData == null ? null : nameData.getOwner();
        } catch (DataException e) {
            log.warn("Could not look up name {}: {}", name, e.getMessage());
            return null;
        }
    }

    /** Signature of the latest transaction for a resource, or null if there is none. */
    public static byte[] latestSignature(String name, String identifier) {
        try (Repository repository = RepositoryManager.getRepository()) {
            return repository.getArbitraryRepository().getLatestSignature(SERVICE, name, identifier);
        } catch (DataException e) {
            log.warn("Could not look up {}/{}: {}", name, identifier, e.getMessage());
            return null;
        }
    }

    /** The repositories QDN holds under a name: its descriptors, not its bundles. */
    public static List<String> listRepositories(String name) {
        List<String> out = new ArrayList<>();
        try (Repository repository = RepositoryManager.getRepository()) {
            for (var resource : repository.getArbitraryRepository().getArbitraryResources(SERVICE, null, List.of(name),
                    false, null, null, false, false, null, null, false)) {
                String identifier = resource.identifier;
                if (identifier != null && !identifier.equals("default") && !identifier.contains("~")) out.add(identifier);
            }
        } catch (DataException e) {
            log.warn("Could not list repositories under {}: {}", name, e.getMessage());
        }
        out.sort(String::compareTo);
        return out;
    }

    // ------------------------------------------------------------------
    // Reading

    /**
     * The directory holding a resource's files. Data not yet on this node is
     * requested from peers; this waits for it up to {@code timeoutMs}.
     *
     * @throws IOException if the resource does not exist or does not arrive in time
     */
    public static Path readResource(String name, String identifier, long timeoutMs) throws IOException {
        return readResource(SERVICE, name, identifier, timeoutMs);
    }

    /** {@link #readResource(String, String, long)} for a resource of another service. */
    public static Path readResource(Service service, String name, String identifier, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                ArbitraryDataReader reader = new ArbitraryDataReader(name, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);
                reader.loadSynchronously(false);
                return reader.getFilePath();
            } catch (MissingDataException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("QDN resource " + name + "/" + identifier + " not available yet", e);
                }
                sleep(1000);
            } catch (DataException e) {
                throw new IOException("QDN resource " + name + "/" + identifier + ": " + e.getMessage(), e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Publishing

    /**
     * Publishes a directory as the resource {@code name/identifier} (a PUT),
     * signed by {@code publisher}, which must own the name. The transaction is
     * imported as unconfirmed; the network propagates and mints it.
     *
     * @return the transaction signature
     */
    public static byte[] publish(PrivateKeyAccount publisher, String name, String identifier, Path directory, String title)
            throws DataException {
        try (Repository repository = RepositoryManager.getRepository()) {
            long fee = BlockChain.getInstance().getUnitFeeAtTimestamp(NTP.getTime());
            ArbitraryDataTransactionBuilder builder = new ArbitraryDataTransactionBuilder(repository,
                    Base58.encode(publisher.getPublicKey()), fee, directory, name, ArbitraryTransactionData.Method.PUT,
                    SERVICE, identifier, title, null, null, null);
            builder.build();

            ArbitraryTransactionData transactionData = builder.getArbitraryTransactionData();
            Transaction transaction = Transaction.fromData(repository, transactionData);
            transaction.sign(publisher);

            Transaction.ValidationResult result = transaction.importAsUnconfirmed();
            if (result != Transaction.ValidationResult.OK) {
                builder.getArbitraryDataFile().deleteAll(true);
                throw new DataException("QDN publish of " + name + "/" + identifier + " invalid: " + result);
            }
            log.info("Published QDN resource {}/{} ({})", name, identifier, Base58.encode(transactionData.getSignature()));
            return transactionData.getSignature();
        }
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
