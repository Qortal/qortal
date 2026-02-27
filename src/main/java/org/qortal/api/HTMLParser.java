package org.qortal.api;


import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.qortal.arbitrary.misc.Service;
public class HTMLParser {

    private static final Logger LOGGER = LogManager.getLogger(HTMLParser.class);

    private String qdnBase;
    private String qdnBaseWithPath;
    private byte[] data;
    private String qdnContext;
    private String resourceId;
    private Service service;
    private String identifier;
    private String path;
    private String theme;
    private String lang;
    private String themePalette;
    private boolean usingCustomRouting;

    public HTMLParser(String resourceId, String inPath, String prefix, boolean includeResourceIdInPrefix, byte[] data,
                      String qdnContext, Service service, String identifier, String theme, boolean usingCustomRouting, String lang,
                      String themePalette) {
        String inPathWithoutFilename = inPath.contains("/") ? inPath.substring(0, inPath.lastIndexOf('/')) : String.format("/%s",inPath);
        this.qdnBase = includeResourceIdInPrefix ? String.format("%s/%s", prefix, resourceId) : prefix;
        this.qdnBaseWithPath = includeResourceIdInPrefix ? String.format("%s/%s%s", prefix, resourceId, inPathWithoutFilename) : String.format("%s%s", prefix, inPathWithoutFilename);
        this.data = data;
        this.qdnContext = qdnContext;
        this.resourceId = resourceId;
        this.service = service;
        this.identifier = identifier;
        this.path = inPath;
        this.theme = theme;
        this.lang = lang;
        this.themePalette = themePalette;
        this.usingCustomRouting = usingCustomRouting;
    }

    public void addAdditionalHeaderTags() {
        String fileContents = new String(data);
        Document document = Jsoup.parse(fileContents);
        Elements head = document.getElementsByTag("head");
        if (!head.isEmpty()) {
            // Add q-apps script tag
            String qAppsScriptElement = String.format("<script src=\"/apps/q-apps.js?time=%d\">", System.currentTimeMillis());
            head.get(0).prepend(qAppsScriptElement);

            // Add q-apps gateway script tag if in gateway mode
            if (Objects.equals(this.qdnContext, "gateway")) {
                String qAppsGatewayScriptElement = String.format("<script src=\"/apps/q-apps-gateway.js?time=%d\">", System.currentTimeMillis());
                head.get(0).prepend(qAppsGatewayScriptElement);
            }

            // Escape and add vars
            String qdnContext = this.qdnContext != null ? this.qdnContext.replace("\\", "").replace("\"","\\\"") : "";
            String service = this.service.toString().replace("\\", "").replace("\"","\\\"");
            String name = this.resourceId != null ? this.resourceId.replace("\\", "").replace("\"","\\\"") : "";
            String identifier = this.identifier != null ? this.identifier.replace("\\", "").replace("\"","\\\"") : "";
            String path = this.path != null ? this.path.replace("\\", "").replace("\"","\\\"") : "";
            String theme = this.theme != null ? this.theme.replace("\\", "").replace("\"","\\\"") : "";
            String lang = this.lang != null ? this.lang.replace("\\", "").replace("\"", "\\\"") : "";
            String themePalette = this.themePaletteJsonOrNull();
            String qdnBase = this.qdnBase != null ? this.qdnBase.replace("\\", "").replace("\"","\\\"") : "";
            String qdnBaseWithPath = this.qdnBaseWithPath != null ? this.qdnBaseWithPath.replace("\\", "").replace("\"","\\\"") : "";
            String qdnContextVar = String.format(
                "<script>var _qdnContext=\"%s\"; var _qdnTheme=\"%s\"; var _qdnLang=\"%s\"; var _qdnThemePalette=%s; var _qdnService=\"%s\"; var _qdnName=\"%s\"; var _qdnIdentifier=\"%s\"; var _qdnPath=\"%s\"; var _qdnBase=\"%s\"; var _qdnBaseWithPath=\"%s\";</script>",
                qdnContext, theme, lang, themePalette, service, name, identifier, path, qdnBase, qdnBaseWithPath
              );
            head.get(0).prepend(qdnContextVar);

            // Add base href tag
            // Exclude the path if this request was routed back to the index automatically
            String baseHref = this.usingCustomRouting ? this.qdnBase : this.qdnBaseWithPath;
            String baseElement = String.format("<base href=\"%s/\">", baseHref);
            head.get(0).prepend(baseElement);

            // Add meta charset tag
            String metaCharsetElement = "<meta charset=\"UTF-8\">";
            head.get(0).prepend(metaCharsetElement);

        }
        
        // For render context with non-default identifier, modify all relative script and link tags
        // to include the identifier query parameter (base tag doesn't reliably preserve query params)
        if (Objects.equals(this.qdnContext, "render") && this.identifier != null && !this.identifier.isBlank() && !this.identifier.equals("default")) {
            String encodedIdentifier = URLEncoder.encode(this.identifier, StandardCharsets.UTF_8);
            
            // Modify script tags
            Elements scripts = document.select("script[src]");
            scripts.forEach(script -> {
                String src = script.attr("src");
                // Only modify relative URLs (not absolute URLs starting with / or http)
                if (!src.startsWith("/") && !src.startsWith("http")) {
                    String newSrc = src.contains("?") ? 
                        src + "&identifier=" + encodedIdentifier : 
                        src + "?identifier=" + encodedIdentifier;
                    script.attr("src", newSrc);
                }
            });
            
            // Modify link tags (CSS, etc.)
            Elements links = document.select("link[href]");
            links.forEach(link -> {
                String href = link.attr("href");
                // Only modify relative URLs
                if (!href.startsWith("/") && !href.startsWith("http")) {
                    String newHref = href.contains("?") ? 
                        href + "&identifier=" + encodedIdentifier : 
                        href + "?identifier=" + encodedIdentifier;
                    link.attr("href", newHref);
                }
            });
        }
        
        String html = document.html();
        this.data = html.getBytes();
    }

    private String themePaletteJsonOrNull() {
        if (this.themePalette == null || this.themePalette.isBlank()) {
            return "null";
        }
        try {
            JSONObject themePaletteJson = new JSONObject(this.themePalette);
            return this.escapeJsonForInlineScript(themePaletteJson.toString());
        } catch (JSONException e) {
            return "null";
        }
    }

    private String escapeJsonForInlineScript(String json) {
        if (json == null) {
            return "null";
        }
        return json
                .replace("<", "\\u003C")
                .replace(">", "\\u003E")
                .replace("&", "\\u0026")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }

    public static boolean isHtmlFile(String path) {
        if (path.endsWith(".html") || path.endsWith(".htm") || path.isEmpty()) {
            return true;
        }
        return false;
    }

    public byte[] getData() {
        return this.data;
    }
}
