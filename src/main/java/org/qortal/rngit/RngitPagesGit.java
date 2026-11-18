package org.qortal.rngit;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The git data the page server shows ({@code pages.py} "Git Data Extraction"),
 * with JGit standing in for the reference's {@code git} commands and keeping
 * their results: {@code rev-parse --verify}, {@code ls-tree -l},
 * {@code cat-file -s}, {@code for-each-ref}, {@code rev-list --count},
 * {@code log}, {@code diff-tree --numstat} and {@code show}.
 */
final class RngitPagesGit {

    /** Cap on a commit page's diff; the reference has none, but runs git with a timeout. */
    static final int MAX_DIFF_BYTES = 1024 * 1024;

    private static final DateTimeFormatter ISO_STRICT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    private RngitPagesGit() {
    }

    static final class TreeEntry {
        String name;
        /** blob, tree, commit (a submodule) or link */
        String type;
        String mode;
        long size;
        String linkTarget;
    }

    static final class BlobInfo {
        long size;
        boolean isTree;
        boolean isBinary;
        boolean isSymlink;
        String symlinkTarget;
    }

    static final class RefInfo {
        String name;
        String hash;
        String shortHash;
        String commitSubject;
        boolean isDefault;
        boolean isAnnotated;
        String tagMessage;
    }

    static final class CommitEntry {
        String hash;
        String subject;
        String author;
        String authorEmail;
        long timestamp;
    }

    static final class FileChange {
        String path;
        String status;
        int additions;
        int deletions;
    }

    static final class CommitInfo {
        List<String> parents = new ArrayList<>();
        String authorName;
        String authorEmail;
        String authorDate;
        String committerName;
        String committerEmail;
        String committerDate;
        String message;
        List<FileChange> files = new ArrayList<>();
        String diff;
    }

    /** Strict UTF-8, as Python's text mode decodes git's output: null on invalid bytes. */
    static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** {@code get_repository_description}: git config, then {@code <repo>.description}. */
    static String description(Path path) {
        try (Repository repository = RngitGit.open(path)) {
            String configured = repository.getConfig().getString("repository", null, "description");
            if (configured != null && !configured.strip().isEmpty()) return configured.strip();
        } catch (Exception ignored) {
            // fall through to the description file
        }
        try {
            Path file = Path.of(path + ".description");
            if (Files.isRegularFile(file)) {
                String description = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (!description.isEmpty()) return description;
            }
        } catch (Exception ignored) {
            // no description
        }
        return null;
    }

    /** {@code resolve_ref}: {@code git rev-parse --verify}, as a lowercase SHA, or null. */
    static String resolveRef(Path path, String ref) {
        if (ref == null || ref.isEmpty() || ref.startsWith("-")) return null;
        try (Repository repository = RngitGit.open(path)) {
            ObjectId id = repository.resolve(ref);
            return id == null ? null : RngitRefs.sanSha(id.name());
        } catch (Exception e) {
            return null;
        }
    }

    /** The tree a resolved object names, peeling tags and commits, or null. */
    private static RevTree peelTree(RevWalk walk, ObjectId id) throws IOException {
        RevObject object = walk.peel(walk.parseAny(id));
        if (object instanceof RevCommit) return ((RevCommit) object).getTree();
        if (object instanceof RevTree) return (RevTree) object;
        return null;
    }

    /** The object at {@code <ref>:<path>}: the tree itself for an empty path. Mode and id, or null. */
    private static Object[] lookup(Repository repository, RevWalk walk, String ref, String filePath) throws IOException {
        RevTree tree = peelTree(walk, ObjectId.fromString(ref));
        if (tree == null) return null;
        if (filePath.isEmpty()) return new Object[]{FileMode.TREE, tree.getId()};
        String normalised;
        try {
            normalised = RngitBrowse.normalise(filePath);
        } catch (RngitBrowse.NotFoundException e) {
            return null;
        }
        if (normalised.isEmpty() || !normalised.equals(filePath)) return null;
        try (TreeWalk entry = TreeWalk.forPath(repository, normalised, tree)) {
            return entry == null ? null : new Object[]{entry.getFileMode(0), entry.getObjectId(0)};
        }
    }

    static String mode(FileMode mode) {
        String octal = Integer.toOctalString(mode.getBits());
        return "0".repeat(Math.max(0, 6 - octal.length())) + octal;
    }

    /** {@code get_tree_entries}: null when the path is no directory at that ref. */
    static List<TreeEntry> treeEntries(Path path, String ref, String treePath) {
        String dir = treePath == null ? "" : strip(treePath, '/');
        try (Repository repository = RngitGit.open(path); RevWalk walk = new RevWalk(repository);
             ObjectReader reader = repository.newObjectReader()) {
            Object[] found = lookup(repository, walk, ref, dir);
            if (found == null || !FileMode.TREE.equals((FileMode) found[0])) return null;

            List<TreeEntry> entries = new ArrayList<>();
            try (TreeWalk walker = new TreeWalk(repository)) {
                walker.addTree((ObjectId) found[1]);
                walker.setRecursive(false);
                while (walker.next()) {
                    FileMode mode = walker.getFileMode(0);
                    TreeEntry entry = new TreeEntry();
                    entry.name = walker.getNameString();
                    entry.mode = mode(mode);
                    entry.type = FileMode.TREE.equals(mode) ? "tree" : FileMode.GITLINK.equals(mode) ? "commit" : "blob";
                    if (mode.getObjectType() == Constants.OBJ_BLOB) {
                        entry.size = reader.getObjectSize(walker.getObjectId(0), Constants.OBJ_BLOB);
                    }
                    if (FileMode.SYMLINK.equals(mode)) {
                        entry.type = "link";
                        String target = utf8(reader.open(walker.getObjectId(0)).getBytes());
                        entry.linkTarget = target == null ? null : target.strip();
                    }
                    entries.add(entry);
                }
            }
            return entries;
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code get_blob_info}, with binary detection by a NUL in the first 8 KiB. */
    static BlobInfo blobInfo(Path path, String ref, String filePath) {
        String file = strip(filePath, '/');
        try (Repository repository = RngitGit.open(path); RevWalk walk = new RevWalk(repository)) {
            Object[] found = lookup(repository, walk, ref, file);
            if (found == null || FileMode.GITLINK.equals((FileMode) found[0])) return null;
            FileMode mode = (FileMode) found[0];
            ObjectLoader loader = repository.open((ObjectId) found[1]);

            BlobInfo info = new BlobInfo();
            info.size = loader.getSize();
            info.isTree = FileMode.TREE.equals(mode);
            info.isSymlink = FileMode.SYMLINK.equals(mode);
            if (info.isSymlink) {
                String target = utf8(loader.getBytes());
                info.symlinkTarget = target == null ? null : target.strip();
            } else if (!info.isTree) {
                byte[] sample = new byte[8192];
                int read;
                try (var in = loader.openStream()) {
                    read = in.readNBytes(sample, 0, sample.length);
                }
                for (int i = 0; i < read; i++) {
                    if (sample[i] == 0) {
                        info.isBinary = true;
                        break;
                    }
                }
            }
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code get_blob_content}: the file as text, or null if it is not valid UTF-8. */
    static String blobContent(Path path, String ref, String filePath) {
        byte[] bytes = blobBytes(path, ref, filePath);
        return bytes == null ? null : utf8(bytes);
    }

    /** A file's bytes at a resolved ref, or null. */
    static byte[] blobBytes(Path path, String ref, String filePath) {
        String file = strip(filePath, '/');
        try (Repository repository = RngitGit.open(path); RevWalk walk = new RevWalk(repository)) {
            Object[] found = lookup(repository, walk, ref, file);
            if (found == null || ((FileMode) found[0]).getObjectType() != Constants.OBJ_BLOB) return null;
            return repository.open((ObjectId) found[1], Constants.OBJ_BLOB).getCachedBytes(Integer.MAX_VALUE);
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code symbolic-ref HEAD} without {@code refs/heads/}, or null. */
    static String defaultBranch(Path path) {
        try (Repository repository = RngitGit.open(path)) {
            Ref head = repository.exactRef(Constants.HEAD);
            if (head == null || !head.isSymbolic()) return null;
            return head.getTarget().getName().replace(Constants.R_HEADS, "");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * {@code get_refs_info} (and {@code get_repository_refs}): branches and
     * tags in refname order, as {@code for-each-ref} lists them. An annotated
     * tag shows its tag object and message, as {@code %(objectname)} and
     * {@code %(subject)} do.
     */
    static Map<String, List<RefInfo>> refs(Path path, String defaultBranch) {
        Map<String, List<RefInfo>> out = new LinkedHashMap<>();
        out.put("heads", new ArrayList<>());
        out.put("tags", new ArrayList<>());
        try (Repository repository = RngitGit.open(path); RevWalk walk = new RevWalk(repository)) {
            List<Ref> refs = new ArrayList<>(repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS));
            refs.addAll(repository.getRefDatabase().getRefsByPrefix(Constants.R_TAGS));
            refs.sort(Comparator.comparing(Ref::getName));
            for (Ref ref : refs) {
                if (ref.getObjectId() == null) continue;
                boolean head = ref.getName().startsWith(Constants.R_HEADS);
                RefInfo info = new RefInfo();
                info.name = Repository.shortenRefName(ref.getName());
                info.hash = ref.getObjectId().name();
                info.shortHash = info.hash.substring(0, 7);
                info.commitSubject = "";
                info.isDefault = info.name.equals(defaultBranch);
                try {
                    RevObject object = walk.parseAny(ref.getObjectId());
                    if (object instanceof RevCommit) {
                        info.commitSubject = ((RevCommit) object).getShortMessage();
                    } else if (object instanceof RevTag) {
                        info.commitSubject = ((RevTag) object).getShortMessage();
                        if (!head) {
                            info.isAnnotated = true;
                            info.tagMessage = info.commitSubject;
                        }
                    }
                } catch (Exception ignored) {
                    // a ref to a missing object keeps an empty subject
                }
                out.get(head ? "heads" : "tags").add(info);
            }
        } catch (Exception ignored) {
            // no refs
        }
        return out;
    }

    /** {@code get_commit_count}: {@code rev-list --count}. */
    static int commitCount(Path path, String ref) {
        try (Repository repository = RngitGit.open(path); RevWalk walk = new RevWalk(repository)) {
            RevObject start = walk.peel(walk.parseAny(ObjectId.fromString(ref)));
            if (!(start instanceof RevCommit)) return 0;
            walk.markStart((RevCommit) start);
            int count = 0;
            for (RevCommit ignored : walk) count++;
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    /** {@code get_commits}: a page of {@code git log [-- path]}, or null on error. */
    static List<CommitEntry> commits(Path path, String ref, String filePath, int skip, int limit) {
        try (Repository repository = RngitGit.open(path); RevWalk walk = new RevWalk(repository)) {
            RevObject start = walk.peel(walk.parseAny(ObjectId.fromString(ref)));
            if (!(start instanceof RevCommit)) return null;
            walk.markStart((RevCommit) start);
            if (filePath != null && !filePath.isEmpty()) {
                String file = strip(filePath, '/');
                if (file.isEmpty()) return null;
                walk.setTreeFilter(AndTreeFilter.create(PathFilter.create(file), TreeFilter.ANY_DIFF));
            }
            walk.setRewriteParents(false);

            List<CommitEntry> out = new ArrayList<>();
            int seen = 0;
            for (RevCommit commit : walk) {
                if (seen++ < skip) continue;
                CommitEntry entry = new CommitEntry();
                entry.hash = commit.name();
                entry.subject = commit.getShortMessage();
                PersonIdent author = commit.getAuthorIdent();
                entry.author = author.getName();
                entry.authorEmail = author.getEmailAddress();
                entry.timestamp = author.getWhenAsInstant().getEpochSecond();
                out.add(entry);
                if (out.size() >= limit) break;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** The type of the object a SHA names ({@code cat-file -t}), or null. */
    static String objectType(Path path, String sha) {
        try (Repository repository = RngitGit.open(path)) {
            return Constants.typeString(repository.open(ObjectId.fromString(sha)).getType());
        } catch (Exception e) {
            return null;
        }
    }

    /** A commit object's raw text ({@code cat-file -p}), or null. */
    static String rawCommit(Path path, String sha) {
        try (Repository repository = RngitGit.open(path)) {
            ObjectLoader loader = repository.open(ObjectId.fromString(sha), Constants.OBJ_COMMIT);
            return utf8(loader.getCachedBytes());
        } catch (Exception e) {
            return null;
        }
    }

    private static String isoStrict(PersonIdent ident) {
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(ident.getTimeZoneOffset() * 60);
        return OffsetDateTime.ofInstant(ident.getWhenAsInstant(), offset).format(ISO_STRICT);
    }

    private static boolean exists(Repository repository, RevTree tree, String file) throws IOException {
        try (TreeWalk entry = TreeWalk.forPath(repository, file, tree)) {
            return entry != null;
        }
    }

    /**
     * {@code get_commit_info}. Changed files follow {@code diff-tree --numstat
     * -r}, which lists none for a root or merge commit; a binary change starts
     * as "R" and becomes "A" or "D" by the parent check, as in the reference.
     * The diff follows {@code show}, with renames detected, but is empty for a
     * merge, where git would show a combined diff.
     */
    static CommitInfo commitInfo(Path path, String sha) {
        try (Repository repository = RngitGit.open(path); RevWalk walk = new RevWalk(repository);
             ObjectReader reader = repository.newObjectReader()) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(sha));
            CommitInfo info = new CommitInfo();
            for (RevCommit parent : commit.getParents()) info.parents.add(parent.name());
            PersonIdent author = commit.getAuthorIdent();
            PersonIdent committer = commit.getCommitterIdent();
            info.authorName = author.getName();
            info.authorEmail = author.getEmailAddress();
            info.authorDate = isoStrict(author);
            info.committerName = committer.getName();
            info.committerEmail = committer.getEmailAddress();
            info.committerDate = isoStrict(committer);
            info.message = commit.getFullMessage().strip();

            RevCommit parent = commit.getParentCount() == 1 ? walk.parseCommit(commit.getParent(0)) : null;

            if (parent != null) {
                try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                    formatter.setRepository(repository);
                    formatter.setDiffComparator(RawTextComparator.DEFAULT);
                    formatter.setDetectRenames(false);
                    for (DiffEntry entry : formatter.scan(treeIterator(reader, parent), treeIterator(reader, commit))) {
                        FileChange change = new FileChange();
                        change.path = entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath();
                        change.status = "M";
                        FileHeader header = formatter.toFileHeader(entry);
                        if (header.getPatchType() != FileHeader.PatchType.UNIFIED) {
                            change.status = "R";
                        } else {
                            for (Edit edit : header.toEditList()) {
                                change.additions += edit.getLengthB();
                                change.deletions += edit.getLengthA();
                            }
                        }
                        if (!exists(repository, parent.getTree(), change.path)) change.status = "A";
                        else if (!exists(repository, commit.getTree(), change.path)) change.status = "D";
                        info.files.add(change);
                    }
                }
            }

            if (commit.getParentCount() <= 1) {
                ByteArrayOutputStream diff = new ByteArrayOutputStream();
                ByteArrayOutputStream part = new ByteArrayOutputStream();
                try (DiffFormatter formatter = new DiffFormatter(part)) {
                    formatter.setRepository(repository);
                    formatter.setDetectRenames(true);
                    AbstractTreeIterator oldTree = parent != null ? treeIterator(reader, parent) : new EmptyTreeIterator();
                    for (DiffEntry entry : formatter.scan(oldTree, treeIterator(reader, commit))) {
                        if (diff.size() > MAX_DIFF_BYTES) break;
                        part.reset();
                        formatter.format(entry);
                        formatter.flush();
                        diff.write(gitBinaryLine(part.toByteArray()));
                    }
                }
                // Python decodes git's output strictly, and the reference shows no commit it cannot decode
                String text = utf8(diff.toByteArray());
                if (text == null) return null;
                info.diff = text.length() > MAX_DIFF_BYTES ? text.substring(0, MAX_DIFF_BYTES) : text;
            }
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    private static final java.util.regex.Pattern JGIT_BINARY =
            java.util.regex.Pattern.compile("--- (.+)\n\\+\\+\\+ (.+)\nBinary files differ\n$");

    /** JGit writes a binary change as ---/+++ lines and "Binary files differ"; git as one line naming both sides. */
    private static byte[] gitBinaryLine(byte[] formatted) {
        String text = new String(formatted, StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = JGIT_BINARY.matcher(text);
        if (!m.find()) return formatted;
        return (text.substring(0, m.start()) + "Binary files " + m.group(1) + " and " + m.group(2) + " differ\n")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static AbstractTreeIterator treeIterator(ObjectReader reader, RevCommit commit) throws IOException {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(reader, commit.getTree().getId());
        return parser;
    }

    /** {@code get_readme_content}: the first README at HEAD, and whether it is Markdown; null if none. */
    static Object[] readme(Path path) {
        String head = resolveRef(path, Constants.HEAD);
        if (head == null) return null;
        Object[][] names = {{"README.mu", false}, {"Readme.mu", false}, {"readme.mu", false}, {"README", false},
                {"readme", false}, {"README.md", true}, {"readme.md", true}, {"README.rst", false},
                {"README.txt", false}, {"readme.rst", false}, {"readme.txt", false}};
        for (Object[] name : names) {
            BlobInfo info = blobInfo(path, head, (String) name[0]);
            if (info == null || info.isTree) continue;
            String content = blobContent(path, head, (String) name[0]);
            if (content != null) return new Object[]{content, name[1]};
        }
        return null;
    }

    /** Python's {@code str.strip(c)} for a single character. */
    static String strip(String s, char c) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == c) start++;
        while (end > start && s.charAt(end - 1) == c) end--;
        return s.substring(start, end);
    }
}
