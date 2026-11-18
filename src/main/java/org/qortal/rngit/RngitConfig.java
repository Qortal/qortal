package org.qortal.rngit;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rngit node configuration file, in the reference's format.
 * <p>
 * The reference reads it with ConfigObj. Only the subset its default config and
 * documentation use is supported: flat {@code [section]} headers (deeper
 * {@code [[section]]} headers are read as flat ones), {@code key = value} lines,
 * {@code #} comments, single- or double-quoted values, and comma-separated
 * lists. That is enough to read an existing rngit node's config unchanged.
 */
@Slf4j
public final class RngitConfig {

    public static final String DEFAULT_CONFIG = String.join("\n",
            "# This is the rngit config file for the Qortal Core git node.",
            "# It uses the same format and options as the reference rngit node.",
            "",
            "[rngit]",
            "  # Announce interval in minutes; 0 disables announces. The",
            "  # repositories destination still answers path requests.",
            "  announce_interval = 360",
            "  # node_name = My Git Node",
            "  # blocked_identities = 9710b86ba12c42d1d8f30f74fe509286",
            "",
            "[repositories]",
            "  # Each entry maps a group name to a directory of bare repositories:",
            "  # public = /path/to/directory/with/git/repositories",
            "",
            "[aliases]",
            "  # alice = 9710b86ba12c42d1d8f30f74fe509286",
            "",
            "[access]",
            "  # By default no permissions are granted for anything.",
            "  # public = r:all, w:9710b86ba12c42d1d8f30f74fe509286",
            "",
            "[qdn]",
            "  # Serve every registered Qortal name as a group of repositories",
            "  # held on QDN: rns://<this node>/<name>/<repo>. Groups configured",
            "  # above take precedence over a name spelled the same.",
            "  enabled = yes",
            "  # With a publisher_key in this directory, the node publishes for the",
            "  # names that key's account owns. After this many bundles, the next",
            "  # push publishes one full bundle replacing them (0: never).",
            "  # compact_after = 16",
            "",
            "[pages]",
            "  # Serve a Nomad Network page node for browsing the repositories,",
            "  # on the nomadnetwork.node destination of this node's identity.",
            "  # Access follows the permissions of each group and repository;",
            "  # visitors that do not identify are the null identity",
            "  # d7db22f63b453c23bb0688dde565b7c1, which blocked_identities can",
            "  # list to require identification.",
            "  #",
            "  # Custom templates go in the templates directory next to this file:",
            "  # base, front, group, repo, tree, blob, commits, commit, refs,",
            "  # stats, releases, release, work and work_doc, each <name>.mu with",
            "  # a {PAGE_CONTENT} variable; an executable template is run and its",
            "  # output used.",
            "  # serve_nomadnet = no",
            "  # Use plain unicode icons instead of Nerd Font icons.",
            "  # unicode_icons = yes",
            "");

    /** The section holding keys that appear before any section header. */
    public static final String ROOT = "";

    private final Map<String, Map<String, String>> sections;

    private RngitConfig(Map<String, Map<String, String>> sections) {
        this.sections = sections;
    }

    /** Loads the config file, writing the default one first if it does not exist. */
    public static RngitConfig loadOrCreate(Path configPath) throws IOException {
        if (!Files.exists(configPath)) {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, DEFAULT_CONFIG, StandardCharsets.UTF_8);
            log.info("Default rngit config created at {}; make any necessary changes there", configPath);
        }

        return parse(Files.readString(configPath, StandardCharsets.UTF_8));
    }

    public static RngitConfig parse(String text) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        // Keys before the first section header live in the root section, as in
        // the flat files rngit writes with ConfigObj (a release's META)
        Map<String, String> current = sections.computeIfAbsent(ROOT, k -> new LinkedHashMap<>());

        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (line.startsWith("[")) {
                String name = line.replaceAll("^\\[+", "").replaceAll("]+\\s*(#.*)?$", "").strip();
                current = sections.computeIfAbsent(name, k -> new LinkedHashMap<>());
                continue;
            }

            int eq = line.indexOf('=');
            if (eq <= 0) {
                log.debug("Ignoring rngit config line without a value: {}", line);
                continue;
            }

            current.put(line.substring(0, eq).strip(), stripInlineComment(line.substring(eq + 1).strip()));
        }

        return new RngitConfig(sections);
    }

    /** Removes a trailing {@code # comment} that is not inside quotes. */
    private static String stripInlineComment(String value) {
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#') {
                return value.substring(0, i).strip();
            }
        }
        return value;
    }

    private static String unquote(String value) {
        String v = value.strip();
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    public boolean hasSection(String section) {
        return sections.containsKey(section);
    }

    /** The keys of a section in file order, or an empty map. */
    public Map<String, String> section(String section) {
        return Collections.unmodifiableMap(sections.getOrDefault(section, Map.of()));
    }

    public String getString(String section, String key, String fallback) {
        String value = sections.getOrDefault(section, Map.of()).get(key);
        return value == null ? fallback : unquote(value);
    }

    public int getInt(String section, String key, int fallback) {
        String value = getString(section, key, null);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            log.warn("Invalid integer \"{}\" for [{}] {} in rngit config, using {}", value, section, key, fallback);
            return fallback;
        }
    }

    public boolean getBool(String section, String key, boolean fallback) {
        String value = getString(section, key, null);
        if (value == null) return fallback;
        switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "yes": case "true": case "on": case "1":
                return true;
            case "no": case "false": case "off": case "0":
                return false;
            default:
                log.warn("Invalid boolean \"{}\" for [{}] {} in rngit config, using {}", value, section, key, fallback);
                return fallback;
        }
    }

    /** A comma-separated value as a list, as ConfigObj's {@code as_list} returns it. */
    public List<String> getList(String section, String key) {
        String value = sections.getOrDefault(section, Map.of()).get(key);
        return value == null ? List.of() : splitList(value);
    }

    /**
     * Writes flat {@code key = value} lines as ConfigObj does for a file without
     * sections: values that would not read back verbatim are quoted.
     */
    public static String writeFlat(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out.append(entry.getKey()).append(" = ").append(quote(entry.getValue())).append('\n');
        }
        return out.toString();
    }

    static String quote(String value) {
        boolean plain = !value.isEmpty() && value.equals(value.strip())
                && value.chars().noneMatch(c -> c == ',' || c == '#' || c == '"' || c == '\'' || c == '\n');
        if (plain) return value;
        return value.indexOf('"') >= 0 ? "'" + value + "'" : "\"" + value + "\"";
    }

    static List<String> splitList(String value) {
        List<String> items = new ArrayList<>();
        StringBuilder item = new StringBuilder();
        char quote = 0;
        for (char c : value.toCharArray()) {
            if (quote != 0) {
                if (c == quote) quote = 0; else item.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ',') {
                addItem(items, item);
            } else {
                item.append(c);
            }
        }
        addItem(items, item);
        return items;
    }

    private static void addItem(List<String> items, StringBuilder item) {
        String s = item.toString().strip();
        if (!s.isEmpty()) items.add(s);
        item.setLength(0);
    }
}
