package org.qortal.rngit;

import io.reticulum.identity.Identity;
import lombok.extern.slf4j.Slf4j;
import org.qortal.rngit.RngitClient.Reply;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.qortal.rngit.RngitProtocol.IDX_REPOSITORY;
import static org.qortal.rngit.RngitProtocol.IDX_RESULT_CODE;
import static org.qortal.rngit.RngitProtocol.PATH_FETCH;
import static org.qortal.rngit.RngitProtocol.PATH_LIST;
import static org.qortal.rngit.RngitProtocol.RES_OK;

/**
 * Fetches all refs of an upstream repository into a bare repository: the
 * reference's {@code git fetch <url> +refs/*:refs/*} for forks and mirrors,
 * together with the upstream's default branch for HEAD.
 * <p>
 * The reference hands every URL to {@code git}, which reaches {@code rns://}
 * through the installed {@code git-remote-rns} helper. Here http(s) goes through
 * JGit, {@code rns://} through {@link RngitClient} on Core's own Reticulum
 * instance, and ssh through the {@code git} command when it is installed.
 */
@Slf4j
public final class RngitUpstream {

    /** Allowed source URL schemes ({@code CLONE_PROTOS}). */
    static final List<String> CLONE_PROTOS = List.of("rns", "http", "https", "ssh");

    /** Refs per fetch request, as the client's default {@code ref_batch_size}. */
    static final int REF_BATCH_SIZE = 25;

    static final long LIST_TIMEOUT_MS = 120_000;
    static final long FETCH_TIMEOUT_MS = 7_200_000;
    static final long GIT_TIMEOUT_SECONDS = 3_600;

    /** The outcome of an upstream fetch. */
    public static final class Fetched {
        public final boolean ok;
        public final String error;
        /** The upstream's default branch, e.g. {@code refs/heads/main}, or null if unknown. */
        public final String headBranch;

        private Fetched(boolean ok, String error, String headBranch) {
            this.ok = ok;
            this.error = error;
            this.headBranch = headBranch;
        }

        static Fetched ok(String headBranch) {
            return new Fetched(true, null, headBranch);
        }

        static Fetched fail(String error) {
            return new Fetched(false, error, null);
        }
    }

    private RngitUpstream() {
    }

    /** The URL scheme as the reference checks it: everything before the first {@code ://}, lowercased. */
    static String scheme(String url) {
        return url.toLowerCase(Locale.ROOT).split("://", -1)[0];
    }

    public static boolean isAllowedSource(String url) {
        return CLONE_PROTOS.contains(scheme(url));
    }

    public static Fetched fetch(Path repository, String url, Identity clientIdentity) {
        switch (scheme(url)) {
            case "http":
            case "https": {
                RngitGit.Result result = RngitGit.fetchHttp(repository, url);
                return result.ok ? Fetched.ok(RngitGit.remoteHeadHttp(url)) : Fetched.fail(result.message);
            }
            case "rns":
                return fetchRns(repository, url, clientIdentity);
            case "ssh":
                return fetchWithGitCommand(repository, url);
            default:
                return Fetched.fail("Prohibited source URL");
        }
    }

    // ------------------------------------------------------------------
    // rns:// — the git-remote-rns list and fetch sequence

    @SuppressWarnings("unchecked")
    static Fetched fetchRns(Path repository, String url, Identity clientIdentity) {
        String[] parts = RngitClient.parseRnsUrl(url);
        if (parts == null) return Fetched.fail("Invalid rns:// URL");
        String repoPath = parts[1] + "/" + parts[2];

        try (RngitClient client = RngitClient.connect(RngitClient.hexToBytes(parts[0]), clientIdentity)) {
            Map<Object, Object> listRequest = new LinkedHashMap<>();
            listRequest.put(IDX_REPOSITORY, repoPath);
            listRequest.put("for_push", false);
            Reply list = client.request(PATH_LIST, listRequest, LIST_TIMEOUT_MS);
            if (list.response == null || list.response.length == 0) return Fetched.fail("Invalid list response from upstream");
            String listText = new String(list.response, 1, list.response.length - 1, StandardCharsets.UTF_8);
            if (list.response[0] != RES_OK) return Fetched.fail("Upstream refused list: " + listText);

            Map<String, String> remoteRefs = new LinkedHashMap<>();
            String headBranch = null;
            for (String line : listText.split("\n")) {
                String[] fields = line.strip().split(" ", 2);
                if (fields.length != 2) continue;
                if (fields[1].equals("HEAD")) {
                    if (fields[0].startsWith("@")) headBranch = fields[0].substring(1);
                    continue;
                }
                remoteRefs.put(fields[1], fields[0]);
            }

            // Upstream objects already here make the bundles thin
            List<String> haves = new ArrayList<>();
            for (String sha : remoteRefs.values()) {
                if (RngitGit.hasObject(repository, sha)) haves.add(sha);
            }

            List<Map<String, Object>> wanted = new ArrayList<>();
            for (Map.Entry<String, String> ref : remoteRefs.entrySet()) {
                String local = RngitGit.resolveRef(repository, ref.getKey());
                if (ref.getValue().equals(local)) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("sha", ref.getValue());
                entry.put("ref", ref.getKey());
                if (local != null) entry.put("have", local);
                wanted.add(entry);
            }

            for (int i = 0; i < wanted.size(); i += REF_BATCH_SIZE) {
                Map<Object, Object> fetchRequest = new LinkedHashMap<>();
                fetchRequest.put(IDX_REPOSITORY, repoPath);
                fetchRequest.put("refs", wanted.subList(i, Math.min(i + REF_BATCH_SIZE, wanted.size())));
                if (!haves.isEmpty()) fetchRequest.put("have", haves);

                Reply fetched = client.request(PATH_FETCH, fetchRequest, FETCH_TIMEOUT_MS);
                if (fetched.response == null) return Fetched.fail("No data in fetch response");

                if (fetched.metadata == null) {
                    if (fetched.response.length > 0 && fetched.response[0] == RES_OK) continue; // nothing new
                    return Fetched.fail("Fetch failed: " + new String(fetched.response, 1, Math.max(0, fetched.response.length - 1), StandardCharsets.UTF_8));
                }
                Object code = fetched.metadata instanceof Map ? ((Map<Object, Object>) fetched.metadata).get((long) IDX_RESULT_CODE) : null;
                if (!(code instanceof Long) || (Long) code != RES_OK) return Fetched.fail("Unknown remote state for batch ref fetch");

                Path bundle = Files.createTempFile("rngit-upstream-", ".bundle");
                try {
                    Files.write(bundle, fetched.response);
                    RngitGit.Result applied = RngitGit.applyBundleAllRefs(repository, bundle);
                    if (!applied.ok) return Fetched.fail("Could not apply upstream bundle: " + applied.message);
                } finally {
                    Files.deleteIfExists(bundle);
                }
            }
            return Fetched.ok(headBranch);
        } catch (IOException e) {
            return Fetched.fail(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // ssh — the git command, as the reference does it

    static Fetched fetchWithGitCommand(Path repository, String url) {
        if (!gitCommandAvailable()) {
            return Fetched.fail("ssh sources need the git command, which is not installed on this node");
        }
        CommandResult fetch = runGit(repository, "fetch", url, "+refs/*:refs/*");
        if (fetch.code != 0) return Fetched.fail(fetch.stderr.strip());

        String headBranch = null;
        CommandResult head = runGit(repository, "ls-remote", "--symref", url, "HEAD");
        if (head.code == 0) {
            for (String line : head.stdout.split("\n")) {
                String[] parts = line.split("\t");
                if (line.startsWith("ref: refs/heads/") && parts.length >= 2 && parts[1].equals("HEAD")) {
                    headBranch = parts[0].substring(5).strip();
                    break;
                }
            }
        }
        return Fetched.ok(headBranch);
    }

    static boolean gitCommandAvailable() {
        try {
            return runGit(null, "--version").code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static final class CommandResult {
        final int code;
        final String stdout;
        final String stderr;

        CommandResult(int code, String stdout, String stderr) {
            this.code = code;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static CommandResult runGit(Path directory, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (directory != null) builder.directory(directory.toFile());
            Process process = builder.start();

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread errReader = new Thread(() -> {
                try {
                    process.getErrorStream().transferTo(err);
                } catch (IOException ignored) {
                    // the process went away; whatever was read is reported
                }
            });
            errReader.start();
            byte[] out = process.getInputStream().readAllBytes();
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new CommandResult(-1, "", "git " + args[0] + " timed out");
            }
            errReader.join();
            return new CommandResult(process.exitValue(), new String(out, StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new CommandResult(-1, "", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "", "interrupted");
        }
    }
}
