package org.qortal.rngit;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Micron, the Nomad Network page markup: the reference's link and escaping
 * helpers ({@code pages.py}) and its Markdown to Micron converter
 * ({@code util.py} {@code MarkdownToMicron}).
 * <p>
 * Code blocks render as the reference renders them without pygments: a
 * literal block, with no syntax highlighting. Lengths count code points, as
 * Python does, and display widths follow {@code wcwidth} for the common cases.
 */
public final class RngitMicron {

    static final String BOLD = "`!";
    static final String ITALIC = "`*";
    static final String CODE_BG = "`BT282828";
    static final String CODE_BG_INLINE = "`BT383838";
    static final String CODE_FG = "`Fddd";
    static final String CODE_RESET = "`f`b";
    static final String LITERAL_START = "`=";
    static final String LITERAL_END = "`=";
    static final String BULLET = "•";

    private static final Pattern HEADER_RE = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern CODE_FENCE_RE = Pattern.compile("^(\\s*)```(.*)$");
    private static final Pattern HORIZONTAL_RULE_RE = Pattern.compile("^(\\s*)(---+|===+|\\*\\*\\*+|___+)\\s*$");
    private static final Pattern UNORDERED_LIST_RE = Pattern.compile("^(\\s*)([-*+])\\s+(.+)$");
    private static final Pattern TABLE_ROW_RE = Pattern.compile("^\\s*\\|?(.+?)\\|?\\s*$");
    private static final Pattern TABLE_SEP_RE = Pattern.compile("^\\s*\\|?(?:\\s*:?-+:?\\s*\\|)+\\s*$");
    private static final Pattern QUOTE_RE = Pattern.compile("^>\\s?(.*)$");
    private static final Pattern LINK_RE = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern INLINE_CODE_RE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD_RE = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
    private static final Pattern ITALIC_RE = Pattern.compile("\\*(.+?)\\*|_(.+?)_");
    private static final Pattern LINK_PLACEHOLDER = Pattern.compile("\u0000LINK(\\d+)\u0000");
    private static final Pattern CODE_PLACEHOLDER = Pattern.compile("\u0000CODE(\\d+)\u0000");

    private static final Pattern[] INVISIBLE = {
            Pattern.compile("`[FB][0-9a-fA-F]{3}"),
            Pattern.compile("`[FB]T[0-9a-fA-F]{6}"),
            Pattern.compile("`[!*_=]"),
            Pattern.compile("`f`b"),
            Pattern.compile("`f"),
            Pattern.compile("`b"),
    };

    private static final String TABLE_H = "─";
    private static final String TABLE_V = "│";
    private static final int TABLE_MIN_COL_WIDTH = 3;

    private final int maxWidth;
    private final String localUrlScope;

    public RngitMicron(int maxWidth, String urlScope) {
        this.maxWidth = maxWidth;
        this.localUrlScope = urlScope == null ? ":/page/" : urlScope;
    }

    // ------------------------------------------------------------------
    // Micron generation helpers (pages.py m_*)

    static String heading(String text, int level) {
        return ">".repeat(level) + text + "\n";
    }

    static String italic(String text) {
        return ITALIC + text + ITALIC;
    }

    static String divider() {
        return "-─\n";
    }

    static String escape(String text) {
        return text.replace("`", "\\`");
    }

    /** {@code urllib.parse.quote_plus} of the value's UTF-8 bytes. */
    static String quotePlus(String value) {
        // Python keeps "-._~" and quotes "*"; URLEncoder keeps "-._*" and quotes "~"
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("*", "%2A").replace("%7E", "~");
    }

    private static String sanitizeLabel(String label) {
        return label.replace("[", "").replace("]", "").replace("`", "");
    }

    private static String fields(Map<String, ?> fields) {
        if (fields == null || fields.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        fields.forEach((k, v) -> parts.add(k + "=" + quotePlus(String.valueOf(v))));
        return "`" + String.join("|", parts);
    }

    /** {@code m_link_r}: a plain link. */
    static String linkR(String label, String path, Map<String, ?> fields) {
        return "`[" + sanitizeLabel(label) + "`:" + path + fields(fields) + "]";
    }

    /** {@code m_link}: a bold link. */
    static String link(String label, String path, Map<String, ?> fields) {
        return "`!" + linkR(label, path, fields) + "`!";
    }

    static String link(String label, String path) {
        return link(label, path, null);
    }

    /** {@code m_link_e}: a bold link to a path on another node. */
    static String linkE(String label, String remote, String path, Map<String, ?> fields) {
        return "`!`[" + sanitizeLabel(label) + "`" + remote + ":" + path + fields(fields) + "]`!";
    }

    /** Link fields in the given order: {@code f("g", group, "r", repo)}. */
    static Map<String, Object> f(Object... keysAndValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) out.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return out;
    }

    /**
     * The reference highlighter without pygments: a literal block, with
     * backslashes doubled as its fallback does.
     */
    static String plainHighlight(String content) {
        String block = "`=\n" + (content == null ? "" : escape(content)) + "\n`=";
        return content == null || content.isEmpty() ? block : block.replace("\\", "\\\\");
    }

    // ------------------------------------------------------------------
    // Display width (wcwidth)

    /** Display columns, as {@code wcwidth.wcswidth}, falling back to the length for non-printables. */
    static int displayWidth(String text) {
        int width = 0;
        int length = 0;
        boolean printable = true;
        for (int cp : text.codePoints().toArray()) {
            length++;
            if (cp < 32 || (cp >= 0x7f && cp < 0xa0)) {
                printable = false;
                continue;
            }
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || cp == 0x200b
                    || (cp >= 0x200c && cp <= 0x200f) || cp == 0xfeff) {
                continue;
            }
            width += isWide(cp) ? 2 : 1;
        }
        return printable ? width : length;
    }

    private static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115f) || (cp >= 0x2e80 && cp <= 0x303e) || (cp >= 0x3041 && cp <= 0x33ff)
                || (cp >= 0x3400 && cp <= 0x4dbf) || (cp >= 0x4e00 && cp <= 0x9fff) || (cp >= 0xa000 && cp <= 0xa4cf)
                || (cp >= 0xac00 && cp <= 0xd7a3) || (cp >= 0xf900 && cp <= 0xfaff) || (cp >= 0xfe30 && cp <= 0xfe4f)
                || (cp >= 0xff00 && cp <= 0xff60) || (cp >= 0xffe0 && cp <= 0xffe6) || (cp >= 0x1f300 && cp <= 0x1f64f)
                || (cp >= 0x1f900 && cp <= 0x1f9ff) || (cp >= 0x20000 && cp <= 0x3fffd);
    }

    private static int cpLength(String s) {
        return s.codePointCount(0, s.length());
    }

    /** The first {@code n} code points of {@code s}. */
    private static String cpPrefix(String s, int n) {
        return s.substring(0, s.offsetByCodePoints(0, Math.min(n, cpLength(s))));
    }

    private static String cpSuffix(String s, int n) {
        return s.substring(s.offsetByCodePoints(0, Math.min(n, cpLength(s))));
    }

    // ------------------------------------------------------------------
    // Markdown to Micron (util.py MarkdownToMicron)

    /** {@code format_block}. */
    public String formatBlock(String text) {
        String[] lines = text.split("\n", -1);
        List<String> result = new ArrayList<>();
        boolean inCodeBlock = false;
        String codeBlockLang = null;
        List<String> codeBuffer = new ArrayList<>();
        boolean inTable = false;
        List<String> tableBuffer = new ArrayList<>();
        boolean inQuote = false;
        List<String> quoteBuffer = new ArrayList<>();

        for (String line : lines) {
            Matcher fence = CODE_FENCE_RE.matcher(line);
            if (fence.matches()) {
                flushQuote(result, quoteBuffer);
                inQuote = false;
                flushTable(result, tableBuffer);
                inTable = false;

                if (!inCodeBlock) {
                    inCodeBlock = true;
                    String hint = fence.group(2);
                    codeBlockLang = hint == null || hint.strip().isEmpty() ? null : hint.strip();
                    codeBuffer = new ArrayList<>();
                } else {
                    flushCode(result, codeBuffer, codeBlockLang);
                    codeBuffer = new ArrayList<>();
                    inCodeBlock = false;
                    codeBlockLang = null;
                }
                continue;
            }

            if (inCodeBlock) {
                codeBuffer.add(line);
                continue;
            }

            Matcher quote = QUOTE_RE.matcher(line);
            if (quote.matches()) {
                if (!inQuote) {
                    flushTable(result, tableBuffer);
                    inTable = false;
                    inQuote = true;
                    quoteBuffer.clear();
                }
                quoteBuffer.add(quote.group(1));
            } else if (inQuote) {
                flushQuote(result, quoteBuffer);
                inQuote = false;
                if (!line.strip().isEmpty()) {
                    if (isTableRow(line)) {
                        inTable = true;
                        tableBuffer.clear();
                        tableBuffer.add(line);
                    } else {
                        result.add(formatLine(line));
                    }
                } else {
                    result.add("");
                }
            } else if (isTableRow(line)) {
                if (!inTable) {
                    inTable = true;
                    tableBuffer.clear();
                }
                tableBuffer.add(line);
            } else {
                if (inTable) {
                    flushTable(result, tableBuffer);
                    inTable = false;
                }
                result.add(formatLine(line));
            }
        }

        if (inQuote) flushQuote(result, quoteBuffer);
        if (inTable) flushTable(result, tableBuffer);
        if (inCodeBlock) flushCode(result, codeBuffer, codeBlockLang);

        return String.join("\n", result);
    }

    private void flushQuote(List<String> result, List<String> quoteBuffer) {
        if (quoteBuffer.isEmpty()) return;
        String formatted = formatInline(String.join(" ", quoteBuffer));
        for (String wrapped : wrapText(formatted, Math.max(maxWidth - 3, 1))) result.add(" │ " + wrapped);
        quoteBuffer.clear();
    }

    private void flushTable(List<String> result, List<String> tableBuffer) {
        if (tableBuffer.isEmpty()) return;
        if (tableBuffer.size() >= 2 && isTableSeparator(tableBuffer.get(1))) {
            result.addAll(formatTable(tableBuffer, "c"));
        } else {
            for (String line : tableBuffer) result.add(formatLine(line));
        }
        tableBuffer.clear();
    }

    private static void flushCode(List<String> result, List<String> codeBuffer, String lang) {
        if (codeBuffer.isEmpty()) return;
        String code = String.join("\n", codeBuffer);
        if (lang != null && lang.equalsIgnoreCase("rawmu")) {
            result.add(code);
        } else if (lang != null) {
            result.add(CODE_BG + CODE_FG);
            result.add(plainHighlight(code));
            result.add(CODE_RESET);
        } else {
            result.add(CODE_BG + CODE_FG);
            result.add(LITERAL_START);
            result.add(escape(code));
            result.add(LITERAL_END);
            result.add(CODE_RESET);
        }
    }

    /** {@code format_line}. */
    String formatLine(String line) {
        line = line.replace("\\", "\\\\");
        if (line.startsWith("-") && !line.startsWith("---") && !line.startsWith("- ")) line = "\\" + line;
        if (line.startsWith("<")) line = "\\" + line;

        if (HORIZONTAL_RULE_RE.matcher(line).matches()) return "-";

        Matcher header = HEADER_RE.matcher(line);
        if (header.matches()) return ">".repeat(Math.min(header.group(1).length(), 6)) + formatInline(header.group(2));

        Matcher item = UNORDERED_LIST_RE.matcher(line);
        if (item.matches()) return item.group(1) + " " + BULLET + " " + formatInline(item.group(3));

        return formatInline(line);
    }

    private static String replaceAll(Pattern pattern, String text, java.util.function.Function<Matcher, String> replacement) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(replacement.apply(m)));
        m.appendTail(out);
        return out.toString();
    }

    /** {@code _format_inline}: links, inline code, bold and italics. */
    String formatInline(String text) {
        List<String[]> links = new ArrayList<>();
        List<String> codes = new ArrayList<>();

        text = replaceAll(LINK_RE, text, m -> {
            links.add(new String[]{m.group(1), m.group(2)});
            return "\u0000LINK" + (links.size() - 1) + "\u0000";
        });
        text = replaceAll(INLINE_CODE_RE, text, m -> {
            codes.add(m.group(1));
            return "\u0000CODE" + (codes.size() - 1) + "\u0000";
        });
        text = replaceAll(BOLD_RE, text, m -> BOLD + (m.group(1) != null ? m.group(1) : m.group(2)) + BOLD);
        text = replaceAll(ITALIC_RE, text, m -> ITALIC + (m.group(1) != null ? m.group(1) : m.group(2)) + ITALIC);

        text = replaceAll(LINK_PLACEHOLDER, text, m -> {
            String[] l = links.get(Integer.parseInt(m.group(1)));
            String[] anchorComponents = l[1].split("#", -1);
            String url = anchorComponents[0];
            String anchor = anchorComponents.length > 1 ? anchorComponents[1] : "";
            if (!url.contains(":/")) {
                url = localUrlScope + url;
                if (!anchor.isEmpty()) url = url + "|anchor=" + anchor;
            }
            return "`_`!`[" + l[0].replace("`", "") + "`" + url + "]`!`_";
        });
        text = replaceAll(CODE_PLACEHOLDER, text, m ->
                CODE_BG_INLINE + CODE_FG + codes.get(Integer.parseInt(m.group(1))).replace("`", "\\`") + CODE_RESET);
        return text;
    }

    private static boolean isTableRow(String line) {
        if (!line.contains("|")) return false;
        Matcher m = TABLE_ROW_RE.matcher(line);
        if (!m.matches()) return false;
        return m.group(1).contains("|") || line.strip().startsWith("|");
    }

    private static boolean isTableSeparator(String line) {
        return line.contains("|") && TABLE_SEP_RE.matcher(line).matches();
    }

    /** {@code format_table}: a box-drawn table, centred. */
    List<String> formatTable(List<String> rows, String align) {
        if (rows.size() < 2) return rows;
        List<String> header = parseTableRow(rows.get(0));
        List<String> alignments = parseTableAlignments(rows.get(1));
        while (alignments.size() < header.size()) alignments.add("left");
        alignments = alignments.subList(0, header.size());

        List<List<String>> dataRows = new ArrayList<>();
        for (int i = 2; i < rows.size(); i++) {
            List<String> cells = parseTableRow(rows.get(i));
            while (cells.size() < header.size()) cells.add("");
            dataRows.add(cells.subList(0, header.size()));
        }

        int columns = header.size();
        int[] widths = new int[columns];
        List<List<String>> all = new ArrayList<>();
        all.add(header);
        all.addAll(dataRows);
        for (List<String> row : all) {
            for (int i = 0; i < row.size(); i++) widths[i] = Math.max(widths[i], visibleWidth(formatInline(row.get(i))));
        }
        for (int i = 0; i < columns; i++) widths[i] = Math.max(widths[i], TABLE_MIN_COL_WIDTH);

        int total = 0;
        for (int w : widths) total += w;
        total += columns * 3 + 1;
        if (total > maxWidth) {
            int excess = total - maxWidth;
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < columns; i++) order.add(i);
            order.sort((a, b) -> Integer.compare(widths[b], widths[a]));
            for (int i : order) {
                if (excess <= 0) break;
                int reduction = Math.min(excess, widths[i] - TABLE_MIN_COL_WIDTH);
                widths[i] -= reduction;
                excess -= reduction;
            }
        }

        List<String> out = new ArrayList<>();
        if (align != null) out.add("`" + align);
        out.add(escape(border("┌", "┬", "┐", widths)));

        StringBuilder headerLine = new StringBuilder(TABLE_V);
        for (int i = 0; i < header.size(); i++) {
            headerLine.append(' ').append(padCell(formatInline(header.get(i)), widths[i], "left")).append(' ').append(TABLE_V);
        }
        out.add(escape(headerLine.toString()));
        out.add(escape(border("├", "┼", "┤", widths)));

        for (List<String> row : dataRows) {
            StringBuilder line = new StringBuilder(TABLE_V);
            for (int i = 0; i < row.size(); i++) {
                line.append(' ').append(padCell(formatInline(row.get(i)), widths[i], alignments.get(i))).append(' ').append(TABLE_V);
            }
            out.add(line.toString());
        }

        out.add(escape(border("└", "┴", "┘", widths)));
        if (align != null) out.add("`a");
        return out;
    }

    private static String border(String left, String middle, String right, int[] widths) {
        StringBuilder b = new StringBuilder(left);
        for (int i = 0; i < widths.length; i++) {
            b.append(TABLE_H.repeat(widths[i] + 2)).append(i < widths.length - 1 ? middle : right);
        }
        return b.toString();
    }

    private static List<String> parseTableRow(String line) {
        line = line.strip();
        if (line.startsWith("|")) line = line.substring(1);
        if (line.endsWith("|")) line = line.substring(0, line.length() - 1);

        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int cp : line.codePoints().toArray()) {
            if (escaped) {
                current.appendCodePoint(cp);
                escaped = false;
            } else if (cp == '\\') {
                escaped = true;
            } else if (cp == '|') {
                cells.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.appendCodePoint(cp);
            }
        }
        cells.add(current.toString().strip());
        return cells;
    }

    private static List<String> parseTableAlignments(String line) {
        List<String> out = new ArrayList<>();
        for (String cell : parseTableRow(line)) {
            cell = cell.strip();
            if (cell.startsWith(":") && cell.endsWith(":")) out.add("center");
            else if (cell.endsWith(":")) out.add("right");
            else out.add("left");
        }
        return out;
    }

    static int visibleWidth(String text) {
        for (Pattern p : INVISIBLE) text = p.matcher(text).replaceAll("");
        return displayWidth(text);
    }

    private static String padCell(String text, int width, String align) {
        text = truncateCell(text, width);
        int padding = width - visibleWidth(text);
        if (align.equals("right")) return " ".repeat(Math.max(padding, 0)) + text;
        if (align.equals("center")) {
            int left = padding / 2;
            return " ".repeat(Math.max(left, 0)) + text + " ".repeat(Math.max(padding - left, 0));
        }
        return text + " ".repeat(Math.max(padding, 0));
    }

    /** {@code _truncate_cell}: cut to fit with an ellipsis, closing any open formatting. */
    private static String truncateCell(String text, int width) {
        if (visibleWidth(text) <= width) return text;

        int point = cpLength(text);
        while (point > 0 && visibleWidth(cpPrefix(text, point)) >= width) point--;
        int[] t = cpPrefix(text, point).codePoints().toArray();

        List<Character> active = new ArrayList<>();
        boolean fg = false;
        boolean bg = false;
        int i = 0;
        while (i < t.length) {
            if (t[i] == '`' && i + 1 < t.length) {
                int tag = t[i + 1];
                if ("!*_=".indexOf(tag) >= 0) {
                    if (!active.remove((Character) (char) tag)) active.add((char) tag);
                    i += 2;
                    continue;
                } else if (tag == 'f') {
                    fg = false;
                    i += 2;
                    continue;
                } else if (tag == 'b') {
                    bg = false;
                    i += 2;
                    continue;
                } else if (tag == 'F' || tag == 'B') {
                    if (tag == 'F') fg = true;
                    else bg = true;
                    i += (i + 2 < t.length && t[i + 2] == 'T') ? 8 : 5;
                    continue;
                }
            }
            i++;
        }

        StringBuilder out = new StringBuilder(new String(t, 0, t.length));
        if (fg) out.append("`f");
        if (bg) out.append("`b");
        for (char c : active) out.append('`').append(c);
        return out.append("…").toString();
    }

    /** {@code _wrap_text}: word wrap by display width, breaking words wider than a line. */
    static List<String> wrapText(String text, int width) {
        List<String> lines = new ArrayList<>();
        if (text.isEmpty()) return List.of("");

        StringBuilder current = new StringBuilder();
        int currentWidth = 0;
        for (String word : text.split(" ", -1)) {
            if (word.isEmpty()) continue;
            int wordWidth = visibleWidth(word);

            if (wordWidth > width) {
                if (current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                    currentWidth = 0;
                }
                String remaining = word;
                while (!remaining.isEmpty()) {
                    int low = 1, high = cpLength(remaining), fit = 0;
                    while (low <= high) {
                        int mid = (low + high) / 2;
                        if (visibleWidth(cpPrefix(remaining, mid)) <= width) {
                            fit = mid;
                            low = mid + 1;
                        } else {
                            high = mid - 1;
                        }
                    }
                    if (fit == 0) fit = 1;
                    lines.add(cpPrefix(remaining, fit));
                    remaining = cpSuffix(remaining, fit);
                }
                continue;
            }

            int space = current.length() > 0 ? 1 : 0;
            if (currentWidth + space + wordWidth <= width) {
                if (current.length() > 0) current.append(' ');
                current.append(word);
                currentWidth += space + wordWidth;
            } else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
                currentWidth = wordWidth;
            }
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines.isEmpty() ? List.of("") : lines;
    }
}
