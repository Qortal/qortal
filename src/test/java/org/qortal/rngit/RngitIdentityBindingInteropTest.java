package org.qortal.rngit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.reticulum.identity.Identity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.apache.commons.codec.binary.Hex.encodeHexString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binding records are interchangeable with reticulum/rngit/rns-identity-binding.py.
 * The fixtures are two throwaway RNS identity files and the record that script
 * created from them for address {@value #ADDRESS}. Ed25519 signatures are
 * deterministic, so Java must reproduce the record exactly and accept it.
 */
class RngitIdentityBindingInteropTest {

    private static final String ADDRESS = "QaliceAddr123";
    private static final Path FIXTURES = Path.of("src/test/resources/rngit");

    @Test
    void javaAndPythonRecordsAgree() throws Exception {
        RngitIdentityBindings.Record python = new ObjectMapper()
                .readValue(FIXTURES.resolve("rns-identity-python.json").toFile(), RngitIdentityBindings.Record.class);

        Identity first = Identity.fromFile(FIXTURES.resolve("test-rns-identity-1"));
        Identity second = Identity.fromFile(FIXTURES.resolve("test-rns-identity-2"));
        RngitIdentityBindings.Record java = RngitIdentityBindings.createRecord(ADDRESS, List.of(first, second));

        assertEquals(python.identities.size(), java.identities.size());
        for (int i = 0; i < java.identities.size(); i++) {
            assertEquals(python.identities.get(i).public_key, java.identities.get(i).public_key);
            assertEquals(python.identities.get(i).signature, java.identities.get(i).signature, "byte-identical signature");
        }

        Set<String> hashes = RngitIdentityBindings.verifiedHashes(python, ADDRESS);
        assertEquals(Set.of(encodeHexString(first.getHash()),
                encodeHexString(second.getHash())), hashes);
        assertTrue(RngitIdentityBindings.verifiedHashes(python, "QsomeoneElse").isEmpty());
    }
}
