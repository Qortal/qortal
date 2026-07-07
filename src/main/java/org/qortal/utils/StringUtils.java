package org.qortal.utils;

public class StringUtils {

    public static String sanitizeString(String input) {
        String sanitized = input
                .replaceAll("[<>:\"/\\\\|?*]", "") // Remove invalid characters
                .replaceAll("^\\s+|\\s+$", "") // Trim leading and trailing whitespace
                .replaceAll("\\s+", "_"); // Replace consecutive whitespace with underscores

        return sanitized;
    }

    /**
     * Format a byte count into a human-readable string using binary units (KB, MB, GB, ...).
     *
     * @param bytes the number of bytes
     * @return a string such as "51.6 GB" or "512 bytes"
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        }

        String[] units = { "KB", "MB", "GB", "TB", "PB", "EB" };
        int exponent = (int) (Math.log(bytes) / Math.log(1024));
        exponent = Math.min(exponent, units.length); // guard against overflow beyond EB

        double value = bytes / Math.pow(1024, exponent);
        return String.format("%.2f %s", value, units[exponent - 1]);
    }
}
