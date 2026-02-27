package org.qortal.test.api;

import org.json.JSONObject;
import org.junit.Test;
import org.qortal.api.HTMLParser;
import org.qortal.arbitrary.misc.Service;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HTMLParserTests {

    private static final String MINIMAL_HTML = "<html><head></head><body>hello</body></html>";

    @Test
    public void testThemePaletteInjectsValidObject() {
        String themePalette = "{\"primary\":\"#111111\",\"surface\":{\"main\":\"#ffffff\"}}";
        HTMLParser parser = new HTMLParser("appname", "/", "/render/APP", true,
                MINIMAL_HTML.getBytes(StandardCharsets.UTF_8), "render", Service.APP,
                "default", "dark", false, "en", themePalette);

        parser.addAdditionalHeaderTags();

        String parsedHtml = new String(parser.getData(), StandardCharsets.UTF_8);
        String themePaletteExpression = extractThemePaletteExpression(parsedHtml);
        JSONObject parsedThemePalette = new JSONObject(themePaletteExpression);

        assertEquals("#111111", parsedThemePalette.getString("primary"));
        assertEquals("#ffffff", parsedThemePalette.getJSONObject("surface").getString("main"));
    }

    @Test
    public void testThemePaletteInvalidJsonFallsBackToNull() {
        HTMLParser parser = new HTMLParser("appname", "/", "/render/APP", true,
                MINIMAL_HTML.getBytes(StandardCharsets.UTF_8), "render", Service.APP,
                "default", "dark", false, "en", "not-json");

        parser.addAdditionalHeaderTags();

        String parsedHtml = new String(parser.getData(), StandardCharsets.UTF_8);
        String themePaletteExpression = extractThemePaletteExpression(parsedHtml);
        assertEquals("null", themePaletteExpression);
    }

    @Test
    public void testThemePaletteNonObjectFallsBackToNull() {
        HTMLParser parser = new HTMLParser("appname", "/", "/render/APP", true,
                MINIMAL_HTML.getBytes(StandardCharsets.UTF_8), "render", Service.APP,
                "default", "dark", false, "en", "[1,2,3]");

        parser.addAdditionalHeaderTags();

        String parsedHtml = new String(parser.getData(), StandardCharsets.UTF_8);
        String themePaletteExpression = extractThemePaletteExpression(parsedHtml);
        assertEquals("null", themePaletteExpression);
    }

    private String extractThemePaletteExpression(String html) {
        Pattern pattern = Pattern.compile("var _qdnThemePalette=([^;]*);");
        Matcher matcher = pattern.matcher(html);
        assertTrue(matcher.find());
        return matcher.group(1);
    }
}

