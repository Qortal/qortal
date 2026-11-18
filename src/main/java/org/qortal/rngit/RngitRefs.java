package org.qortal.rngit;

import java.util.List;

/**
 * Ref name and SHA validation, as {@code RNS/Utilities/rngit/util.py}
 * {@code san_ref}, {@code san_refs} and {@code san_sha} do it, following
 * git-check-ref-format. Each returns its input when valid and null otherwise.
 */
public final class RngitRefs {

    private RngitRefs() {
    }

    public static String sanRef(String ref) {
        if (ref == null) return null;
        if (ref.startsWith("-") || ref.startsWith("/")) return null;
        if (ref.endsWith("/") || ref.endsWith(".")) return null;

        if (ref.contains(" ")) return null;
        if (!ref.contains("/")) return null;
        if (ref.contains("..") || ref.contains("/.") || ref.contains("//") || ref.contains("\\")) return null;

        for (String comp : ref.split("/", -1)) {
            if (comp.endsWith(".lock")) return null;
        }

        // Any control character, and everything below '(' as the reference checks it
        if (!ref.chars().allMatch(c -> c >= 40)) return null;
        if (ref.indexOf('\u007f') >= 0) return null;
        for (String bad : List.of("~", "^", ":", "?", "*", "[", "@{")) {
            if (ref.contains(bad)) return null;
        }
        if (ref.equals("@")) return null;

        return ref;
    }

    /** Null unless every ref is valid. */
    public static List<String> sanRefs(List<String> refs) {
        if (refs == null) return null;
        for (String ref : refs) {
            if (sanRef(ref) == null) return null;
        }
        return refs;
    }

    /** At least 40 characters of hex. */
    public static String sanSha(String sha) {
        if (sha == null || sha.length() < 40 || sha.length() % 2 != 0) return null;
        for (char c : sha.toCharArray()) {
            if (Character.digit(c, 16) < 0) return null;
        }
        return sha;
    }
}
