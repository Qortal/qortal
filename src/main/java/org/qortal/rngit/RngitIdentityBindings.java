package org.qortal.rngit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reticulum.identity.Identity;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.qortal.arbitrary.misc.Service;
import org.qortal.crypto.Crypto;
import org.qortal.data.group.GroupMemberData;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.apache.commons.codec.binary.Hex.encodeHexString;

/**
 * Bindings between Qortal accounts and Reticulum identities.
 * <p>
 * An account publishes, under any name it owns, the QDN resource
 * {@value #IDENTIFIER} (service {@code JSON}) listing its RNS identities. Each
 * entry carries the identity's public key and the identity's signature over
 * {@code qortal-rns-identity-binding/1:<address>}, proving the account holder
 * controls that identity. The account's side is proven by QDN: only the
 * account owning a name can publish under it, and only records whose
 * transaction was signed by the account itself count, so a record left under
 * a name that has since been sold binds nothing to the buyer.
 * <p>
 * Bindings are per account, not per name, so they survive name sales; access
 * rules that name a Qortal name resolve through its current owner.
 */
@Slf4j
public final class RngitIdentityBindings {

    public static final Service SERVICE = Service.JSON;
    public static final String IDENTIFIER = "rns-identity";
    public static final String FORMAT = "rns-identity/1";
    static final String MESSAGE_PREFIX = "qortal-rns-identity-binding/1:";
    static final long CACHE_MILLIS = 5 * 60_000L;
    static final long READ_TIMEOUT_MS = 15_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The published record. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Record {
        public String format = FORMAT;
        public String address;
        public List<Entry> identities = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Entry {
        /** The identity's 64-byte public key (X25519 then Ed25519), hex. */
        public String public_key;
        /** The identity's signature over the binding message, hex. */
        public String signature;
    }

    private static final class Cached {
        final Set<String> hashes;
        final long expires;

        Cached(Set<String> hashes, long expires) {
            this.hashes = hashes;
            this.expires = expires;
        }
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    static byte[] bindingMessage(String address) {
        return (MESSAGE_PREFIX + address).getBytes(StandardCharsets.UTF_8);
    }

    /** A record binding the given identities, which must hold private keys, to an account. */
    public static Record createRecord(String address, List<Identity> identities) {
        Record record = new Record();
        record.address = address;
        for (Identity identity : identities) {
            Entry entry = new Entry();
            entry.public_key = encodeHexString(identity.getPublicKey());
            entry.signature = encodeHexString(identity.sign(bindingMessage(address)));
            record.identities.add(entry);
        }
        return record;
    }

    public static void writeRecord(Record record, Path file) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), record);
    }

    /** The identity hashes (lowercase hex) a record validly binds to {@code address}. */
    static Set<String> verifiedHashes(Record record, String address) {
        Set<String> hashes = new HashSet<>();
        if (record == null || !FORMAT.equals(record.format) || !address.equals(record.address) || record.identities == null) {
            return hashes;
        }
        for (Entry entry : record.identities) {
            try {
                Identity identity = new Identity(false);
                if (!identity.loadPublicKey(Hex.decodeHex(entry.public_key))) continue;
                if (identity.validate(Hex.decodeHex(entry.signature), bindingMessage(address))) {
                    hashes.add(encodeHexString(identity.getHash()));
                }
            } catch (DecoderException | RuntimeException e) {
                log.debug("Ignoring malformed identity binding for {}", address, e);
            }
        }
        return hashes;
    }

    /** RNS identity hashes bound to an account, from records under the names it owns. */
    public Set<String> boundIdentities(String address) {
        if (address == null) return Set.of();
        Cached cached = cache.get(address);
        if (cached != null && cached.expires > System.currentTimeMillis()) return cached.hashes;

        Set<String> hashes = new HashSet<>();
        try (Repository repository = RepositoryManager.getRepository()) {
            for (NameData name : repository.getNameRepository().getNamesByOwner(address)) {
                byte[] signature = repository.getArbitraryRepository().getLatestSignature(SERVICE, name.getName(), IDENTIFIER);
                if (signature == null) continue;
                TransactionData transaction = repository.getTransactionRepository().fromSignature(signature);
                if (transaction == null || !address.equals(Crypto.toAddress(transaction.getCreatorPublicKey()))) continue;
                try {
                    Path dir = RngitQdn.readResource(SERVICE, name.getName(), IDENTIFIER, READ_TIMEOUT_MS);
                    Record record = JSON.readValue(singleFile(dir).toFile(), Record.class);
                    hashes.addAll(verifiedHashes(record, address));
                } catch (Exception e) {
                    log.debug("Could not read identity binding under {}: {}", name.getName(), e.getMessage());
                }
            }
        } catch (DataException e) {
            log.warn("Could not look up identity bindings for {}: {}", address, e.getMessage());
            return Set.of();
        }

        Set<String> result = Set.copyOf(hashes);
        cache.put(address, new Cached(result, System.currentTimeMillis() + CACHE_MILLIS));
        return result;
    }

    /** A JSON resource is a single file; QDN keeps its own name for it. */
    private static Path singleFile(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return dir;
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Empty resource " + dir));
        }
    }

    /** Whether any member of a Qortal group has bound the identity. */
    public boolean isBoundToGroupMember(int groupId, String remoteHashHex) {
        try (Repository repository = RepositoryManager.getRepository()) {
            for (GroupMemberData member : repository.getGroupRepository().getGroupMembers(groupId)) {
                if (boundIdentities(member.getMember()).contains(remoteHashHex)) return true;
            }
        } catch (DataException e) {
            log.warn("Could not look up members of group {}: {}", groupId, e.getMessage());
        }
        return false;
    }
}
