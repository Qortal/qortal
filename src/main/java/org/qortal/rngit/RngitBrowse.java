package org.qortal.rngit;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only browsing of a bare repository, for the REST API and Q-Apps:
 * commit history, directory listings, file contents and commits with their
 * changes. Results are plain maps and lists, ready to serialise as JSON.
 */
public final class RngitBrowse {

    public static final int MAX_LOG_LIMIT = 500;
    public static final long MAX_BLOB_BYTES = 10L * 1024 * 1024;
    public static final int MAX_DIFF_BYTES = 1024 * 1024;

    /** Something the caller asked for does not exist: an unknown ref, path or object. */
    public static final class NotFoundException extends Exception {
        public NotFoundException(String message) {
            super(message);
        }
    }

    private RngitBrowse() {
    }

    private static ObjectId resolveCommit(Repository repository, String ref) throws IOException, NotFoundException {
        String revision = ref == null || ref.isEmpty() ? Constants.HEAD : ref;
        if (revision.startsWith("-") || revision.contains("..")) throw new NotFoundException("Invalid ref " + revision);
        ObjectId id = repository.resolve(revision + "^{commit}");
        if (id == null) throw new NotFoundException("Unknown ref " + revision);
        return id;
    }

    private static Map<String, Object> person(PersonIdent ident) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", ident.getName());
        out.put("email", ident.getEmailAddress());
        out.put("timestamp", ident.getWhenAsInstant().toEpochMilli());
        return out;
    }

    private static Map<String, Object> commitSummary(RevCommit commit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sha", commit.name());
        List<String> parents = new ArrayList<>();
        for (RevCommit parent : commit.getParents()) parents.add(parent.name());
        out.put("parents", parents);
        out.put("author", person(commit.getAuthorIdent()));
        out.put("committer", person(commit.getCommitterIdent()));
        out.put("summary", commit.getShortMessage());
        out.put("signed", commit.getRawGpgSignature() != null);
        return out;
    }

    /** Commits reachable from {@code ref}, newest first. */
    public static List<Map<String, Object>> log(Path path, String ref, int offset, int limit) throws IOException, NotFoundException {
        try (Repository repository = RngitGit.open(path); RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(resolveCommit(repository, ref)));
            List<Map<String, Object>> out = new ArrayList<>();
            int skipped = 0;
            int max = Math.max(1, Math.min(limit, MAX_LOG_LIMIT));
            for (RevCommit commit : walk) {
                if (skipped++ < Math.max(0, offset)) continue;
                out.add(commitSummary(commit));
                if (out.size() >= max) break;
            }
            return out;
        }
    }

    /** The entries of a directory at {@code ref}; the root directory for an empty path. */
    public static List<Map<String, Object>> tree(Path path, String ref, String dirPath) throws IOException, NotFoundException {
        try (Repository repository = RngitGit.open(path); RevWalk walk = new RevWalk(repository);
             ObjectReader reader = repository.newObjectReader()) {
            RevCommit commit = walk.parseCommit(resolveCommit(repository, ref));
            ObjectId treeId = commit.getTree().getId();
            String dir = normalise(dirPath);
            if (!dir.isEmpty()) {
                try (TreeWalk entry = TreeWalk.forPath(repository, dir, commit.getTree())) {
                    if (entry == null || !entry.getFileMode(0).equals(FileMode.TREE)) throw new NotFoundException("No directory " + dir);
                    treeId = entry.getObjectId(0);
                }
            }

            List<Map<String, Object>> out = new ArrayList<>();
            try (TreeWalk entries = new TreeWalk(repository)) {
                entries.addTree(treeId);
                entries.setRecursive(false);
                while (entries.next()) {
                    FileMode mode = entries.getFileMode(0);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", entries.getNameString());
                    item.put("path", dir.isEmpty() ? entries.getNameString() : dir + "/" + entries.getNameString());
                    item.put("type", mode.equals(FileMode.TREE) ? "tree" : mode.equals(FileMode.GITLINK) ? "commit" : "blob");
                    item.put("mode", Integer.toOctalString(mode.getBits()));
                    item.put("sha", entries.getObjectId(0).name());
                    if (mode.getObjectType() == Constants.OBJ_BLOB) {
                        item.put("size", reader.getObjectSize(entries.getObjectId(0), Constants.OBJ_BLOB));
                    }
                    out.add(item);
                }
            }
            return out;
        }
    }

    /** A file's contents at {@code ref}, up to {@link #MAX_BLOB_BYTES}. */
    public static byte[] blob(Path path, String ref, String filePath) throws IOException, NotFoundException {
        try (Repository repository = RngitGit.open(path); RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(resolveCommit(repository, ref));
            String file = normalise(filePath);
            if (file.isEmpty()) throw new NotFoundException("No file path given");
            try (TreeWalk entry = TreeWalk.forPath(repository, file, commit.getTree())) {
                if (entry == null || entry.getFileMode(0).getObjectType() != Constants.OBJ_BLOB) throw new NotFoundException("No file " + file);
                ObjectLoader loader = repository.open(entry.getObjectId(0), Constants.OBJ_BLOB);
                if (loader.getSize() > MAX_BLOB_BYTES) {
                    throw new IOException("File " + file + " is " + loader.getSize() + " bytes, over the limit of " + MAX_BLOB_BYTES);
                }
                return loader.getBytes();
            }
        }
    }

    /** A commit with the files it changed and, if asked, its unified diff against its first parent. */
    public static Map<String, Object> commit(Path path, String sha, boolean withDiff) throws IOException, NotFoundException {
        if (RngitRefs.sanSha(sha) == null) throw new NotFoundException("Invalid commit " + sha);
        try (Repository repository = RngitGit.open(path); RevWalk walk = new RevWalk(repository);
             ObjectReader reader = repository.newObjectReader()) {
            RevCommit commit;
            try {
                commit = walk.parseCommit(ObjectId.fromString(sha));
            } catch (org.eclipse.jgit.errors.MissingObjectException | IllegalArgumentException e) {
                throw new NotFoundException("Unknown commit " + sha);
            }

            Map<String, Object> out = commitSummary(commit);
            out.put("message", commit.getFullMessage());

            AbstractTreeIterator newTree = treeIterator(reader, commit);
            AbstractTreeIterator oldTree = commit.getParentCount() > 0
                    ? treeIterator(reader, walk.parseCommit(commit.getParent(0)))
                    : new EmptyTreeIterator();

            ByteArrayOutputStream diff = new ByteArrayOutputStream();
            try (DiffFormatter formatter = new DiffFormatter(withDiff ? diff : org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE)) {
                formatter.setRepository(repository);
                formatter.setDetectRenames(true);
                List<Map<String, Object>> changes = new ArrayList<>();
                for (DiffEntry entry : formatter.scan(oldTree, newTree)) {
                    Map<String, Object> change = new LinkedHashMap<>();
                    change.put("type", entry.getChangeType().name().toLowerCase());
                    change.put("oldPath", entry.getChangeType() == DiffEntry.ChangeType.ADD ? null : entry.getOldPath());
                    change.put("newPath", entry.getChangeType() == DiffEntry.ChangeType.DELETE ? null : entry.getNewPath());
                    changes.add(change);
                    if (withDiff && diff.size() <= MAX_DIFF_BYTES) formatter.format(entry);
                }
                out.put("changes", changes);
            }
            if (withDiff) {
                String text = diff.toString(java.nio.charset.StandardCharsets.UTF_8);
                boolean truncated = text.length() > MAX_DIFF_BYTES;
                out.put("diff", truncated ? text.substring(0, MAX_DIFF_BYTES) : text);
                out.put("diffTruncated", truncated);
            }
            return out;
        }
    }

    private static AbstractTreeIterator treeIterator(ObjectReader reader, RevCommit commit) throws IOException {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(reader, commit.getTree().getId());
        return parser;
    }

    /** A repository path without leading/trailing slashes; refuses to climb out with {@code ..}. */
    static String normalise(String path) throws NotFoundException {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        for (String part : p.split("/")) {
            if (part.equals("..") || part.equals(".")) throw new NotFoundException("Invalid path " + path);
        }
        return p;
    }
}
