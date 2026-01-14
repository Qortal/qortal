package org.qortal.api.proxy.resource;

import org.qortal.api.ApiError;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.HTMLParser;
import org.qortal.arbitrary.misc.Service;
import org.qortal.controller.DevProxyManager;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.util.Enumeration;


@Path("/")
public class DevProxyServerResource {

    @Context HttpServletRequest request;
    @Context HttpServletResponse response;
    @Context ServletContext context;


    @GET
    public HttpServletResponse getProxyIndex() {
        return this.proxy("/");
    }

    @GET
    @Path("{path:.*}")
    public HttpServletResponse getProxyPath(@PathParam("path") String inPath) {
        return this.proxy(inPath);
    }

    private HttpServletResponse proxy(String inPath) {
        try {
            String source = DevProxyManager.getInstance().getSourceHostAndPort();

            if (!inPath.startsWith("/")) {
                inPath = "/" + inPath;
            }

            String queryString = request.getQueryString() != null ? "?" + request.getQueryString() : "";

            // Open URL
            URL url = new URL(String.format("http://%s%s%s", source, inPath, queryString));
            HttpURLConnection con = (HttpURLConnection) url.openConnection();

            // Proxy the request data
            this.proxyRequestToConnection(request, con);

            try {
                // Make the request and proxy the response code
                response.setStatus(con.getResponseCode());
            }
            catch (ConnectException e) {

                // Try converting localhost / 127.0.0.1 to IPv6 [::1]
                if (source.startsWith("localhost") || source.startsWith("127.0.0.1")) {
                    int port = 80;
                    String[] parts = source.split(":");
                    if (parts.length > 1) {
                        port = Integer.parseInt(parts[1]);
                    }
                    source = String.format("[::1]:%d", port);
                }

                // Retry connection
                url = new URL(String.format("http://%s%s%s", source, inPath, queryString));
                con = (HttpURLConnection) url.openConnection();
                this.proxyRequestToConnection(request, con);
                response.setStatus(con.getResponseCode());
            }

            // Proxy the response data back to the caller
            this.proxyConnectionToResponse(con, response, inPath);

        } catch (IOException e) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, e.getMessage());
        }

        return response;
    }

    private void proxyRequestToConnection(HttpServletRequest request, HttpURLConnection con) throws ProtocolException {
        // Proxy the request method
        con.setRequestMethod(request.getMethod());

        // Proxy the request headers
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            String headerValue = request.getHeader(headerName);
            con.setRequestProperty(headerName, headerValue);
        }

        // TODO: proxy any POST parameters from "request" to "con"
    }

    private void proxyConnectionToResponse(HttpURLConnection con, HttpServletResponse response, String inPath) throws IOException {
        // Proxy the response headers
        for (int i = 0; ; i++) {
            String headerKey = con.getHeaderFieldKey(i);
            String headerValue = con.getHeaderField(i);
            if (headerKey != null && headerValue != null) {
                response.addHeader(headerKey, headerValue);
                continue;
            }
            break;
        }

        // Read the response body
        InputStream inputStream = con.getInputStream();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        byte[] data = outputStream.toByteArray(); // TODO: limit file size that can be read into memory

        // Close the streams
        outputStream.close();
        inputStream.close();

        // Extract filename
        String filename = "";
        if (inPath.contains("/")) {
            String[] parts = inPath.split("/");
            if (parts.length > 0) {
                filename = parts[parts.length - 1];
            }
        }

        String lang = request.getParameter("lang");
        if (lang == null || lang.isBlank()) {
            lang = "en"; // fallback
        }

        String theme = request.getParameter("theme");
        if (theme == null || theme.isBlank()) {
            theme = "light";
        }

        // Parse and modify output if needed
        if (HTMLParser.isHtmlFile(filename)) {
            // HTML file - needs to be parsed
            HTMLParser htmlParser = new HTMLParser("", inPath, "", false, data, "proxy", Service.APP, null, theme , true, lang);
            htmlParser.addAdditionalHeaderTags();
            response.addHeader("Content-Security-Policy", "default-src 'self' 'unsafe-inline' 'unsafe-eval'; media-src 'self' data: blob:; img-src 'self' data: blob:; connect-src 'self' ws:; font-src 'self' data:;");
            response.setContentType(con.getContentType());
            response.setContentLength(htmlParser.getData().length);
            response.getOutputStream().write(htmlParser.getData());
        }
        else {
            // Regular file - can be streamed directly
            response.addHeader("Content-Security-Policy", "default-src 'self'");
            response.setContentType(con.getContentType());
            response.setContentLength(data.length);
            response.getOutputStream().write(data);
        }
    }

}
