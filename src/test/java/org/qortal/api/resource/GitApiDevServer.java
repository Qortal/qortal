package org.qortal.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jgit.api.Git;
import org.qortal.rngit.RngitRepositories;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.WebApplicationException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;

/**
 * A development server for the rngit Q-App (reticulum/qapp/rngit): serves the app and
 * answers /git/* through the real {@link GitResource}, over a sample
 * repository it creates in a configured group, so the app sees exactly the
 * JSON Core produces. Not part of Core; no Qortal repository or RNS needed.
 * <p>
 * With {@code --qdn-demo} it also presents the sample as a QDN repository
 * (clone URL, a staged change, a descriptor), for the Staged and Settings tabs.
 * <p>
 * With {@code --hub-shim} a stand-in for Hub's qortalRequest signs in as the
 * owner of "demo" and logs publish requests to the console instead of sending them.
 * <p>
 * Usage: {@code GitApiDevServer <qapp-dir> <port> [--qdn-demo] [--hub-shim]}
 */
public class GitApiDevServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Path app = Path.of(args[0]).toAbsolutePath();
        int port = Integer.parseInt(args[1]);
        List<String> flags = List.of(args).subList(2, args.length);
        boolean qdnDemo = flags.contains("--qdn-demo");
        boolean hubShim = flags.contains("--hub-shim");

        Path root = Files.createTempDirectory("rngit-qapp-dev");
        Path group = Files.createDirectories(root.resolve("demo"));
        createSample(root.resolve("work"), group.resolve("hello"));
        Files.writeString(group.resolve("hello.allowed"), "r:all\n");

        RngitRepositories repositories = new RngitRepositories(Map.of(), Map.of(), Set.of());
        repositories.loadGroup("demo", group);
        GitResource.registry = () -> repositories;
        GitResource.server = () -> null;

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            try {
                handle(exchange, app, qdnDemo, hubShim);
            } catch (Exception e) {
                send(exchange, 500, "text/plain", e.toString().getBytes(StandardCharsets.UTF_8));
            }
        });
        // Concurrent requests, so the screenshot delay below does not block the app's API calls
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("rngit Q-App dev server on http://127.0.0.1:" + port + "/ (sample: demo/hello)");
    }

    private static void createSample(Path work, Path bare) throws Exception {
        try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("master").call()) {
            Files.createDirectories(work.resolve("src"));
            Files.writeString(work.resolve("README.md"), "# hello\n\nA sample repository for the rngit Q-App.\n");
            Files.writeString(work.resolve("src/main.py"), "def main():\n    print(\"hello\")\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit").setAuthor("Ann", "ann@example.invalid").setSign(false).call();
            Files.writeString(work.resolve("src/main.py"), "def main():\n    print(\"hello, Qortal\")\n\nmain()\n");
            Files.write(work.resolve("logo.bin"), new byte[]{0, 1, 2, 3, 0, 5});
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Greet Qortal\n\nAnd call main().").setAuthor("Bob", "bob@example.invalid").setSign(false).call();
            git.tag().setName("v1.0").call();
            git.branchCreate().setName("feature").call();
        }
        Git.cloneRepository().setURI(work.toUri().toString()).setDirectory(bare.toFile()).setBare(true).setCloneAllBranches(true).call().close();
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    /** A stand-in for Hub's qortalRequest: signed in as the owner of "demo"; publishes are logged, not sent. */
    private static final String HUB_SHIM = "<script>window.qortalRequest = async r => { console.log('qortalRequest', r);"
            + " if (r.action === 'GET_USER_ACCOUNT') return { address: 'QdemoOwnerAddress', publicKey: 'demo' };"
            + " if (r.action === 'GET_ACCOUNT_NAMES') return [{ name: 'demo' }];"
            + " return true; };</script>";

    private static void handle(HttpExchange exchange, Path app, boolean qdnDemo, boolean hubShim) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        Map<String, String> q = query(exchange.getRequestURI().getRawQuery());

        if (!path.startsWith("/git/")) {
            if (path.equals("/__dev-delay")) {
                // Holds the page's load event, which headless screenshots wait for, until the app has rendered
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                send(exchange, 200, "image/gif", java.util.Base64.getDecoder().decode("R0lGODlhAQABAAAAACw="));
                return;
            }
            if (hubShim && path.equals("/names/demo")) {
                send(exchange, 200, "application/json", "{\"name\":\"demo\",\"owner\":\"QdemoOwnerAddress\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.startsWith("/names/")) {
                send(exchange, 404, "application/json", "{\"error\":401,\"message\":\"name unknown\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Path file = app.resolve(path.equals("/") ? "index.html" : path.substring(1)).normalize();
            if (!file.startsWith(app) || !Files.isRegularFile(file)) {
                send(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String type = file.toString().endsWith(".js") ? "text/javascript" : file.toString().endsWith(".css") ? "text/css" : "text/html";
            byte[] body = Files.readAllBytes(file);
            if (type.equals("text/html")) {
                body = new String(body, StandardCharsets.UTF_8)
                        .replace("<script src=\"app.js\">", (hubShim ? HUB_SHIM : "") + "<script src=\"app.js\">")
                        .replace("</body>", "<img src=\"/__dev-delay\" alt=\"\" style=\"display:none\"></body>")
                        .getBytes(StandardCharsets.UTF_8);
            }
            send(exchange, 200, type, body);
            return;
        }

        String[] p = path.substring(5).split("/");
        for (int i = 0; i < p.length; i++) p[i] = URLDecoder.decode(p[i], StandardCharsets.UTF_8);
        GitResource resource = new GitResource();
        resource.request = mock(HttpServletRequest.class);
        try {
            if (p.length == 1) {
                json(exchange, resource.listRepositories(p[0]));
            } else if (p.length == 2 && p[0].equals("identity")) {
                json(exchange, "{\"address\":\"" + p[1] + "\",\"identities\":[]}");
            } else if (p.length == 2) {
                String summary = resource.getRepository(p[0], p[1]);
                if (qdnDemo) {
                    Map<String, Object> m = JSON.readValue(summary, LinkedHashMap.class);
                    m.put("qdn", true);
                    m.put("cloneUrl", "rns://0123456789abcdef0123456789abcdef/" + p[0] + "/" + p[1]);
                    m.put("description", "Demo repository");
                    m.put("bundles", 2);
                    summary = JSON.writeValueAsString(m);
                }
                json(exchange, summary);
            } else if (p[2].equals("log")) {
                json(exchange, resource.getLog(p[0], p[1], q.get("ref"), Integer.parseInt(q.getOrDefault("offset", "0")),
                        Integer.parseInt(q.getOrDefault("limit", "50"))));
            } else if (p[2].equals("tree")) {
                json(exchange, resource.getTree(p[0], p[1], q.get("ref"), q.get("path")));
            } else if (p[2].equals("blob")) {
                send(exchange, 200, "application/octet-stream", resource.getBlob(p[0], p[1], q.get("ref"), q.get("path")));
            } else if (p[2].equals("commit")) {
                json(exchange, resource.getCommit(p[0], p[1], p[3], "true".equals(q.get("diff"))));
            } else if (qdnDemo && p[2].equals("staged")) {
                json(exchange, JSON.writeValueAsString(List.of(Map.of("id", 1, "kind", "bundle", "ref", "refs/heads/master",
                        "base", "a".repeat(40), "sha", "b".repeat(40), "force", false, "pusher", "33333333333333333333333333333333",
                        "created", System.currentTimeMillis(), "applies", true))));
            } else if (qdnDemo && p[2].equals("descriptor")) {
                json(exchange, JSON.writeValueAsString(Map.of("format", "rngit-qdn/1", "repository", p[1], "head", "refs/heads/master",
                        "refs", Map.of("refs/heads/master", "b".repeat(40)), "bundles", List.of(p[1] + "~b~1", p[1] + "~b~2"),
                        "allowed", "adm:11111111111111111111111111111111\nw:name:devuser", "description", "Demo repository")));
            } else {
                send(exchange, 404, "application/json", "{\"message\":\"no such endpoint\"}".getBytes(StandardCharsets.UTF_8));
            }
        } catch (WebApplicationException e) {
            // Core serialises its ApiErrorMessage as {"error": code, "message": text}
            Object entity = e.getResponse().getEntity();
            String message = e.getMessage();
            if (entity != null) {
                try {
                    var field = entity.getClass().getDeclaredField("message");
                    field.setAccessible(true);
                    message = String.valueOf(field.get(entity));
                } catch (ReflectiveOperationException ignored) {
                    message = String.valueOf(entity);
                }
            }
            send(exchange, e.getResponse().getStatus(), "application/json",
                    ("{\"message\":" + JSON.writeValueAsString(message) + "}").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        send(exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
