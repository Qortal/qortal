package org.qortal.rngit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rngit config file subset: the reference's own default config must parse as ConfigObj reads it. */
class RngitConfigTest {

    /** The reference's {@code __default_rngit_config__}, uncommented parts as shipped. */
    private static final String REFERENCE_DEFAULT = String.join("\n",
            "# This is the default rngit config file.",
            "[rngit]",
            "  announce_interval = 360",
            "[repositories]",
            "  internal = /path/to/directory/with/git/repositories",
            "  public = /another/path/to/directory/with/git/repositories",
            "[aliases]",
            "[access]",
            "  public = r:all, w:9710b86ba12c42d1d8f30f74fe509286",
            "  internal = rw:9710b86ba12c42d1d8f30f74fe509286",
            "[pages]",
            "[logging]",
            "  loglevel = 4");

    @Test
    void parsesReferenceDefault() {
        RngitConfig config = RngitConfig.parse(REFERENCE_DEFAULT);

        assertEquals(360, config.getInt("rngit", "announce_interval", 0));
        assertEquals(List.of("internal", "public"), List.copyOf(config.section("repositories").keySet()));
        assertEquals(List.of("r:all", "w:9710b86ba12c42d1d8f30f74fe509286"), config.getList("access", "public"));
        assertEquals(List.of("rw:9710b86ba12c42d1d8f30f74fe509286"), config.getList("access", "internal"));
        assertTrue(config.hasSection("pages"));
        assertTrue(config.section("aliases").isEmpty());
    }

    @Test
    void quotesCommentsAndBooleans() {
        RngitConfig config = RngitConfig.parse(String.join("\n",
                "[rngit]",
                "node_name = \"My # Git Node\"   # trailing comment",
                "record_stats = yes",
                "blocked_identities = 'aa', bb , ,cc",
                "[[nested]]",
                "key = value"));

        assertEquals("My # Git Node", config.getString("rngit", "node_name", null));
        assertTrue(config.getBool("rngit", "record_stats", false));
        assertEquals(List.of("aa", "bb", "cc"), config.getList("rngit", "blocked_identities"));
        assertEquals("value", config.getString("nested", "key", null));
        assertFalse(config.getBool("rngit", "missing", false));
        assertEquals(7, config.getInt("rngit", "node_name", 7), "a non-integer falls back");
    }

    /** A release META written by the reference's ConfigObj, quoting where it must. */
    @Test
    void readsAndWritesFlatReleaseMeta() {
        RngitConfig meta = RngitConfig.parse(String.join("\n",
                "tag = \"v1.0,rc#1\"", "hash = abc", "created = 1700000000", "status = draft", "created_by = it's", ""));

        assertEquals("v1.0,rc#1", meta.getString(RngitConfig.ROOT, "tag", null));
        assertEquals(1700000000, meta.getInt(RngitConfig.ROOT, "created", 0));
        assertEquals("it's", meta.getString(RngitConfig.ROOT, "created_by", null));

        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("tag", "v1.0,rc#1");
        values.put("status", "published");
        values.put("note", "say \"hi\"");
        String written = RngitConfig.writeFlat(values);
        assertEquals("tag = \"v1.0,rc#1\"\nstatus = published\nnote = 'say \"hi\"'\n", written);

        RngitConfig reread = RngitConfig.parse(written);
        assertEquals("v1.0,rc#1", reread.getString(RngitConfig.ROOT, "tag", null));
        assertEquals("say \"hi\"", reread.getString(RngitConfig.ROOT, "note", null));
    }
}
