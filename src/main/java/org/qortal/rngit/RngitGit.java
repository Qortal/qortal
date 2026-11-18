package org.qortal.rngit;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.BundleWriter;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TrackingRefUpdate;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportBundleStream;
import org.eclipse.jgit.transport.URIish;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The git operations an rngit node performs, on bare repositories via JGit.
 * <p>
 * The reference shells out to {@code git}; each method names the command it
 * stands in for. Bundles are git's v2 bundle format, which is what
 * {@code git bundle create} writes and {@code git-remote-rns} reads.
 */
@Slf4j
public final class RngitGit {

    private RngitGit() {
    }

    /** The outcome of a git operation: success, or a reason to report. */
    public static final class Result {
        public final boolean ok;
        public final String message;

        private Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        static Result ok() {
            return new Result(true, null);
        }

        static Result fail(String message) {
            return new Result(false, message);
        }
    }

    static Repository open(Path path) throws IOException {
        return new FileRepositoryBuilder().setGitDir(path.toFile()).setMustExist(true).build();
    }

    /** {@code git rev-parse --git-dir} succeeds. */
    public static boolean isGitRepository(Path path) {
        try (Repository repository = open(path)) {
            return repository.getObjectDatabase().exists();
        } catch (Exception e) {
            return false;
        }
    }

    /** {@code git config --bool core.bare} is true. */
    public static boolean isBareRepository(Path path) {
        try (Repository repository = open(path)) {
            return repository.isBare()
                    && repository.getConfig().getBoolean("core", null, "bare", true);
        } catch (Exception e) {
            return false;
        }
    }

    /** {@code git init --bare}. */
    public static void initBare(Path path) throws Exception {
        Git.init().setBare(true).setDirectory(path.toFile()).call().close();
    }

    /**
     * The body of a {@code /git/list} response: {@code git for-each-ref
     * --format "%(objectname) %(refname)"} lines, then {@code @<head> HEAD}.
     * HEAD falls back to {@code master} when it is not a symbolic ref, as the
     * reference's HEAD file read does.
     */
    public static String listRefs(Path path) throws IOException {
        try (Repository repository = open(path)) {
            String headRef = "master";
            Path headFile = path.resolve(Constants.HEAD);
            if (Files.isRegularFile(headFile)) {
                String head = Files.readString(headFile).strip();
                if (head.startsWith("ref: ")) headRef = head.substring(5).strip();
            }

            List<Ref> refs = new ArrayList<>(repository.getRefDatabase().getRefsByPrefix(Constants.R_REFS));
            refs.sort(Comparator.comparing(Ref::getName));

            StringBuilder out = new StringBuilder();
            for (Ref ref : refs) {
                if (ref.getObjectId() == null) continue;
                out.append(ref.getObjectId().name()).append(' ').append(ref.getName()).append('\n');
            }
            out.append('@').append(headRef).append(" HEAD\n");
            return out.toString();
        }
    }

    /** {@code git cat-file -t <sha>} succeeds. */
    public static boolean hasObject(Repository repository, String sha) {
        try {
            return repository.getObjectDatabase().has(ObjectId.fromString(sha));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * {@code git bundle create <file> <ref>... ^<have>...}: writes a bundle of the
     * given refs, excluding everything reachable from the haves the repository
     * knows. Returns false, writing nothing, when the bundle would be empty —
     * {@code git} refuses with "empty bundle" and the reference then answers with
     * a bare {@code RES_OK}.
     *
     * @param refs  ref names to include
     * @param haves SHAs the client already has; unknown ones are skipped
     */
    public static boolean createBundle(Path path, List<String> refs, List<String> haves, Path bundleFile) throws IOException {
        try (Repository repository = open(path); RevWalk walk = new RevWalk(repository)) {
            BundleWriter writer = new BundleWriter(repository);
            List<RevCommit> tips = new ArrayList<>();

            for (String refName : refs) {
                Ref ref = repository.exactRef(refName);
                if (ref == null || ref.getObjectId() == null) {
                    throw new IOException("Unknown ref " + refName);
                }
                writer.include(ref.getName(), ref.getObjectId());
                RevObject peeled = walk.peel(walk.parseAny(ref.getObjectId()));
                if (peeled instanceof RevCommit) tips.add((RevCommit) peeled);
            }

            List<RevCommit> assumed = new ArrayList<>();
            for (String sha : haves) {
                if (!hasObject(repository, sha)) {
                    log.warn("Client have-sha {} not found in repository, skipping", sha);
                    continue;
                }
                RevObject have = walk.peel(walk.parseAny(ObjectId.fromString(sha)));
                if (have instanceof RevCommit) {
                    writer.assume((RevCommit) have);
                    assumed.add((RevCommit) have);
                }
            }

            if (isEmpty(walk, tips, assumed)) {
                return false;
            }

            try (OutputStream out = Files.newOutputStream(bundleFile)) {
                writer.writeBundle(NullProgressMonitor.INSTANCE, out);
            }
            return true;
        }
    }

    /** No commit reachable from the tips is outside what the haves already cover. */
    private static boolean isEmpty(RevWalk walk, List<RevCommit> tips, List<RevCommit> haves) throws IOException {
        if (tips.isEmpty()) return false;
        walk.reset();
        for (RevCommit tip : tips) walk.markStart(walk.parseCommit(tip));
        for (RevCommit have : haves) walk.markUninteresting(walk.parseCommit(have));
        return walk.next() == null;
    }

    /**
     * {@code git bundle verify} then {@code git fetch <bundle> <local>:<remote> [--force]}:
     * applies a pushed bundle. A non-fast-forward update is refused unless forced.
     */
    public static Result applyBundle(Path path, Path bundleFile, String localRef, String remoteRef, boolean force) {
        try (Repository repository = open(path);
             InputStream in = new BufferedInputStream(Files.newInputStream(bundleFile));
             Transport transport = new TransportBundleStream(repository, new URIish(bundleFile.toUri().toString()), in)) {
            RefSpec spec = new RefSpec(localRef + ":" + remoteRef).setForceUpdate(force);
            FetchResult result = transport.fetch(NullProgressMonitor.INSTANCE, List.of(spec));

            TrackingRefUpdate update = result.getTrackingRefUpdate(remoteRef);
            if (update == null) {
                // Nothing to change: the ref already points at the bundle's tip
                return Result.ok();
            }
            switch (update.getResult()) {
                case NEW: case FAST_FORWARD: case FORCED: case NO_CHANGE:
                    return Result.ok();
                case REJECTED:
                    return Result.fail("Non-fast-forward update rejected (force required)");
                default:
                    return Result.fail("Ref update " + update.getResult());
            }
        } catch (TransportException e) {
            return Result.fail("Could not verify bundle: " + e.getMessage());
        } catch (Exception e) {
            return Result.fail(e.toString());
        }
    }

    /** The current SHA of a ref, or null if it does not exist ({@code git rev-parse}). */
    public static String resolveRef(Path path, String ref) throws IOException {
        try (Repository repository = open(path)) {
            Ref r = repository.exactRef(ref);
            return r == null || r.getObjectId() == null ? null : r.getObjectId().name();
        }
    }

    public static boolean hasObject(Path path, String sha) throws IOException {
        try (Repository repository = open(path)) {
            return hasObject(repository, sha);
        }
    }

    /** {@code git update-ref <ref> <sha>}. */
    public static Result updateRef(Path path, String ref, String sha) {
        try (Repository repository = open(path)) {
            RefUpdate update = repository.updateRef(ref);
            update.setNewObjectId(ObjectId.fromString(sha));
            RefUpdate.Result result = update.forceUpdate();
            switch (result) {
                case NEW: case FORCED: case FAST_FORWARD: case NO_CHANGE:
                    return Result.ok();
                default:
                    return Result.fail("Ref update " + result);
            }
        } catch (Exception e) {
            return Result.fail(e.toString());
        }
    }

    /** {@code git update-ref -d <ref>}. */
    public static Result deleteRef(Path path, String ref) {
        try (Repository repository = open(path)) {
            RefUpdate update = repository.updateRef(ref);
            update.setForceUpdate(true);
            RefUpdate.Result result = update.delete();
            switch (result) {
                case FORCED: case NO_CHANGE: case FAST_FORWARD:
                    return Result.ok();
                default:
                    return Result.fail("Ref delete " + result);
            }
        } catch (Exception e) {
            return Result.fail(e.toString());
        }
    }

    /**
     * {@code git fetch <bundle> +refs/*:refs/*}: applies every ref in a bundle,
     * forced, as an upstream fetch for a fork or mirror does.
     */
    public static Result applyBundleAllRefs(Path path, Path bundleFile) {
        try (Repository repository = open(path);
             InputStream in = new BufferedInputStream(Files.newInputStream(bundleFile));
             Transport transport = new TransportBundleStream(repository, new URIish(bundleFile.toUri().toString()), in)) {
            FetchResult result = transport.fetch(NullProgressMonitor.INSTANCE, List.of(new RefSpec("+refs/*:refs/*")));
            for (TrackingRefUpdate update : result.getTrackingRefUpdates()) {
                switch (update.getResult()) {
                    case NEW: case FAST_FORWARD: case FORCED: case NO_CHANGE:
                        break;
                    default:
                        return Result.fail("Ref update " + update.getLocalName() + " " + update.getResult());
                }
            }
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(e.toString());
        }
    }

    /** {@code git fetch <url> +refs/*:refs/*} over http(s), via JGit. */
    public static Result fetchHttp(Path path, String url) {
        // Git.wrap leaves the repository open on close, so it is closed here
        try (Repository repository = open(path); Git git = Git.wrap(repository)) {
            git.fetch().setRemote(url).setRefSpecs(new RefSpec("+refs/*:refs/*")).setTimeout(300).call();
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /** The branch a remote's HEAD points to ({@code git ls-remote --symref <url> HEAD}), or null. */
    public static String remoteHeadHttp(String url) {
        try {
            Map<String, Ref> refs = Git.lsRemoteRepository().setRemote(url).setTimeout(30).callAsMap();
            Ref head = refs.get(Constants.HEAD);
            if (head != null && head.isSymbolic() && head.getTarget().getName().startsWith(Constants.R_HEADS)) {
                return head.getTarget().getName();
            }
        } catch (Exception e) {
            log.warn("Could not query remote HEAD from {}: {}", url, e.getMessage());
        }
        return null;
    }

    /**
     * {@code __update_head_to_source_default}: point HEAD at the upstream's
     * default branch if it exists locally, else at the first local branch.
     */
    public static boolean updateHead(Path path, String targetBranch) {
        try (Repository repository = open(path)) {
            String target = targetBranch;
            if (target != null && repository.exactRef(target) == null) {
                log.warn("Remote default branch {} not found locally, using fallback", target);
                target = null;
            }
            if (target == null) {
                List<Ref> branches = new ArrayList<>(repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS));
                if (branches.isEmpty()) return false;
                branches.sort(Comparator.comparing(Ref::getName));
                target = branches.get(0).getName();
            }
            RefUpdate.Result result = repository.updateRef(Constants.HEAD).link(target);
            return result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED || result == RefUpdate.Result.NO_CHANGE;
        } catch (Exception e) {
            log.error("Error updating HEAD of {}", path, e);
            return false;
        }
    }

    // The rngit repository metadata lives in the repository's git config,
    // where the reference keeps it: repository.rngit.type,
    // repository.rngit.upstream.source and repository.rngit.upstream.sync.

    /** {@code fork} or {@code mirror}, or null for a plain repository. */
    public static String rngitType(Path path) {
        try (Repository repository = open(path)) {
            return repository.getConfig().getString("repository", "rngit", "type");
        } catch (Exception e) {
            return null;
        }
    }

    public static String upstreamSource(Path path) {
        try (Repository repository = open(path)) {
            return repository.getConfig().getString("repository", "rngit.upstream", "source");
        } catch (Exception e) {
            return null;
        }
    }

    /** Unix time of the last successful upstream sync, or 0. */
    public static long upstreamSynced(Path path) {
        try (Repository repository = open(path)) {
            return repository.getConfig().getLong("repository", "rngit.upstream", "sync", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    public static void setUpstream(Path path, String type, String source) throws IOException {
        try (Repository repository = open(path)) {
            StoredConfig config = repository.getConfig();
            config.setString("repository", "rngit", "type", type);
            config.setString("repository", "rngit.upstream", "source", source);
            config.save();
        }
    }

    public static boolean setUpstreamSynced(Path path) {
        try (Repository repository = open(path)) {
            StoredConfig config = repository.getConfig();
            config.setLong("repository", "rngit.upstream", "sync", System.currentTimeMillis() / 1000);
            config.save();
            return true;
        } catch (Exception e) {
            log.error("Could not set upstream sync time for {}", path, e);
            return false;
        }
    }

    /** Ref names and SHAs, for tests and diagnostics. */
    static Map<String, String> refs(Path path) throws IOException {
        try (Repository repository = open(path)) {
            Map<String, String> out = new TreeMap<>();
            for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_REFS)) {
                if (ref.getObjectId() != null) out.put(ref.getName(), ref.getObjectId().name());
            }
            return out;
        }
    }
}
