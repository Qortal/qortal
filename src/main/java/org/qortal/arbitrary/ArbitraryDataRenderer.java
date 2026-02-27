package org.qortal.arbitrary;

import com.google.common.io.Resources;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.HTMLParser;
import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.misc.Service;
import org.qortal.controller.Controller;
import org.qortal.settings.Settings;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ArbitraryDataRenderer {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataRenderer.class);

    private final String resourceId;
    private final ResourceIdType resourceIdType;
    private final Service service;
    private final String identifier;
    private String theme = "light";
    private String lang = "en"; 
    private String themePalette = null;
    private String inPath;
    private final String secret58;
    private final String prefix;
    private final boolean includeResourceIdInPrefix;
    private final boolean async;
    private final String qdnContext;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final ServletContext context;

    public ArbitraryDataRenderer(String resourceId, ResourceIdType resourceIdType, Service service, String identifier,
                                 String inPath, String secret58, String prefix, boolean includeResourceIdInPrefix, boolean async, String qdnContext,
                                 HttpServletRequest request, HttpServletResponse response, ServletContext context) {

        this.resourceId = resourceId;
        this.resourceIdType = resourceIdType;
        this.service = service;
        this.identifier = identifier != null ? identifier : "default";
        this.inPath = inPath;
        this.secret58 = secret58;
        this.prefix = prefix;
        this.includeResourceIdInPrefix = includeResourceIdInPrefix;
        this.async = async;
        this.qdnContext = qdnContext;
        this.request = request;
        this.response = response;
        this.context = context;
    }

    public HttpServletResponse render() {
        if (!inPath.startsWith("/")) {
            inPath = "/" + inPath;
        }

        // Don't render data if QDN is disabled
        if (!Settings.getInstance().isQdnEnabled()) {
            return ArbitraryDataRenderer.getResponse(response, 500, "QDN is disabled in settings");
        }

        ArbitraryDataReader arbitraryDataReader;
        try {
            arbitraryDataReader = new ArbitraryDataReader(resourceId, resourceIdType, service, identifier);
            arbitraryDataReader.setSecret58(secret58); // Optional, used for loading encrypted file hashes only

            if (!arbitraryDataReader.isCachedDataAvailable()) {
                // If async is requested, show a loading screen whilst build is in progress
                if (async) {
                    arbitraryDataReader.loadAsynchronously(false, 10);
                    return this.getLoadingResponse(service, resourceId, identifier, theme);
                }

                // Otherwise, loop until we have data
                int attempts = 0;
                while (!Controller.isStopping()) {
                    attempts++;
                    if (!arbitraryDataReader.isBuilding()) {
                        try {
                            arbitraryDataReader.loadSynchronously(false);
                            break;
                        } catch (MissingDataException e) {
                            if (attempts > 5) {
                                // Give up after 5 attempts
                                return ArbitraryDataRenderer.getResponse(response, 404, "Data unavailable. Please try again later.");
                            }
                        }
                    }
                    Thread.sleep(3000L);
                }
            }

        } catch (Exception e) {
            LOGGER.info(String.format("Unable to load %s %s: %s", service, resourceId, e.getMessage()));
            return ArbitraryDataRenderer.getResponse(response, 500, "Error 500: Internal Server Error");
        }

        java.nio.file.Path path = arbitraryDataReader.getFilePath();
        if (path == null) {
            return ArbitraryDataRenderer.getResponse(response, 404, "Error 404: File Not Found");
        }
        String unzippedPath = path.toString();

        // Set path automatically for single file resources (except for apps, which handle routing differently)
        String[] files = ArrayUtils.removeElement(new File(unzippedPath).list(), ".qortal");
        if (files.length == 1 && this.service != Service.APP) {
            // This is a single file resource
            inPath = files[0];
        }

        try {
            String filename = this.getFilename(unzippedPath, inPath);
            Path filePath = Paths.get(unzippedPath, filename);
            boolean usingCustomRouting = false;
            if (Files.isDirectory(filePath) && (!inPath.endsWith("/"))) {
                inPath = inPath + "/";
                filename = this.getFilename(unzippedPath, inPath);
                filePath = Paths.get(unzippedPath, filename);
            }
            
            // If the file doesn't exist, we may need to route the request elsewhere, or cleanup
            if (!Files.exists(filePath)) {
                if (inPath.equals("/")) {
                    // Delete the unzipped folder if no index file was found
                    try {
                        FileUtils.deleteDirectory(new File(unzippedPath));
                    } catch (IOException e) {
                        LOGGER.debug("Unable to delete directory: {}", unzippedPath, e);
                    }
                }

                // If this is an app, then forward all unhandled requests to the index, to give the app the option to route it
                if (this.service == Service.APP) {
                    // Locate index file
                    List<String> indexFiles = ArbitraryDataRenderer.indexFiles();
                    for (String indexFile : indexFiles) {
                        Path indexPath = Paths.get(unzippedPath, indexFile);
                        if (Files.exists(indexPath)) {
                            // Forward request to index file
                            filePath = indexPath;
                            filename = indexFile;
                            usingCustomRouting = true;
                            break;
                        }
                    }
                }
            }

            if (HTMLParser.isHtmlFile(filename)) {
                // HTML file - needs to be parsed
                byte[] data = Files.readAllBytes(filePath); // TODO: limit file size that can be read into memory
                String encodedResourceId;

                if (resourceIdType == ResourceIdType.NAME) {
                    encodedResourceId = resourceId.replace(" ", "%20");
                } else {
                    encodedResourceId = resourceId;
                }
                HTMLParser htmlParser = new HTMLParser(encodedResourceId, inPath, prefix, includeResourceIdInPrefix, data, qdnContext, service, identifier, theme, usingCustomRouting, lang, themePalette);
                htmlParser.addAdditionalHeaderTags();
                response.addHeader(
                    "Content-Security-Policy",
                    "default-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
                    "font-src 'self' data:; " +

                    // allow localhost for media
                    "media-src 'self' data: blob: http://127.0.0.1:* http://localhost:*; " +

                    "img-src 'self' data: blob:; " +
                    "connect-src 'self' wss: blob:;"
                );
                response.setContentType(context.getMimeType(filename));
                response.setContentLength(htmlParser.getData().length);
                response.getOutputStream().write(htmlParser.getData());
            }
            else {
                // Regular file - can be streamed directly
                File file = filePath.toFile();
                FileInputStream inputStream = new FileInputStream(file);
                response.addHeader("Content-Security-Policy", "default-src 'self'");
                response.setContentType(context.getMimeType(filename));
                int bytesRead, length = 0;
                byte[] buffer = new byte[10240];
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    response.getOutputStream().write(buffer, 0, bytesRead);
                    length += bytesRead;
                }
                response.setContentLength(length);
                inputStream.close();
            }
            return response;
        } catch (FileNotFoundException | NoSuchFileException e) {
            LOGGER.info("Unable to serve file: {}", e.getMessage());
        } catch (IOException e) {
            LOGGER.info("Unable to serve file at path {}: {}", inPath, e.getMessage());
        }

        return ArbitraryDataRenderer.getResponse(response, 404, "Error 404: File Not Found");
    }

    private String getFilename(String directory, String userPath) {
        if (userPath == null || userPath.endsWith("/") || userPath.isEmpty()) {
            // Locate index file
            List<String> indexFiles = ArbitraryDataRenderer.indexFiles();
            for (String indexFile : indexFiles) {
                Path path = Paths.get(directory, indexFile);
                if (Files.exists(path)) {
                    return userPath + indexFile;
                }
            }
        }
        return userPath;
    }

    private HttpServletResponse getLoadingResponse(Service service, String name, String identifier, String theme) {
        String responseString = "";
        URL url = Resources.getResource("loading/index.html");
        try {
            responseString = Resources.toString(url, StandardCharsets.UTF_8);

            // Replace vars
            responseString = responseString.replace("%%SERVICE%%", service.toString());
            responseString = responseString.replace("%%NAME%%", name);
            responseString = responseString.replace("%%IDENTIFIER%%", identifier);
            responseString = responseString.replace("%%THEME%%", theme);

        } catch (IOException e) {
            LOGGER.info("Unable to show loading screen: {}", e.getMessage());
        }
        return ArbitraryDataRenderer.getResponse(response, 503, responseString);
    }

    public static HttpServletResponse getResponse(HttpServletResponse response, int responseCode, String responseString) {
        try {
            byte[] responseData = responseString.getBytes();
            response.setStatus(responseCode);
            response.setContentLength(responseData.length);
            response.getOutputStream().write(responseData);
        } catch (IOException e) {
            LOGGER.info("Error writing {} response", responseCode);
        }
        return response;
    }

    public static List<String> indexFiles() {
        List<String> indexFiles = new ArrayList<>();
        indexFiles.add("index.html");
        indexFiles.add("index.htm");
        indexFiles.add("default.html");
        indexFiles.add("default.htm");
        indexFiles.add("home.html");
        indexFiles.add("home.htm");
        return indexFiles;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }
    public void setLang(String lang) {
        this.lang = lang;
    }
    public void setThemePalette(String themePalette) {
        this.themePalette = themePalette;
    }

}
