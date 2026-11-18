# Quirks in the reference rngit

Behaviour of the reference rngit and its Nomad Network pages
(`RNS/Utilities/rngit/server.py`, `pages.py`) that Core's port
(`org.qortal.rngit`) found while matching it. Each one says what Core does and
where it is described.

Checked against the reference at RNS 1.5.5 (Reticulum commit `e40191b3`,
2026-09-30). Repository and work quirks were confirmed by running a stock
Python rngit node alongside Core; page quirks by `pages-ab.sh`, which compares
both implementations' responses byte for byte.

This list is for the people working on Core, kept in case someone wants to
raise these with the reference's author personally. It is not meant to be
filed upstream as is.

## Repository, permissions and work documents

| Quirk | Effect | Core | Where |
|---|---|---|---|
| Saving a repository's permissions (`rperms` set) checks *group* admin, while the step before it checks repository admin | A repository's creator can read its rules but not save them | Checks repository admin, as the documentation says | `RngitServer.java`, class javadoc |
| `resolve_group_permission` does not check the blocklist | A blocked identity can still create repositories in a group that grants `c:all` | Refuses blocked identities in every handler | `RngitServer.java`, class javadoc; `RngitRepositories.resolveGroupPermission` |
| Deleting a created work document unlinks a `.allowed` file that only proposals have | Fails with "Remote error"; created documents cannot be deleted | Deletes it | `RngitWork.java`, class javadoc; `run.sh` step 29 |
| Deleting or commenting on an unknown work document ID raises | "Remote error" | Answers "Document not found" | `RngitWork.java`, class javadoc |
| Cloning a freshly created, empty repository: the bundle names a ref that does not exist yet | The clone fails | Same (kept for parity) | `run.sh`, step 2 comment |

## Nomad Network pages

| Quirk | Effect | Core | Where |
|---|---|---|---|
| A mirror or fork that was never synced has sync time 0 | Shows "synced 20729d ago", counted from 1970 | Shows no sync age | `RngitPages.java`, class javadoc; `pages-ab.sh` `KNOWN` |
| A release tag from a request is joined to the releases path unchecked | The release page and `/file/artifact` accept `../` in a tag (they then need a `META` file there) | Tags must be a single path component | `RngitPages.java`, class javadoc |
| The releases page takes `splitlines()[0]` of each preview | A published release without notes makes the page fail | Lists it without a preview | `RngitPages.java`, class javadoc |
| The first thanks creates the thanks file, but the function then returns 0 | The first thanks shows 0 | Shows the stored count | `RngitPages.java`, class javadoc |
| `/file/workdoc` for a missing document logs `doc_id[:128]` on an int | Raises `TypeError`; no response | No response either | `pages-ab.sh` (counted as a pass) |
| A comment's format is read from the comment's top level, where none is stored | Comments always render as Markdown, even micron ones | Same (kept for parity) | `RngitPages.workDocPage` |
| A literal code block escapes backticks inside `` `= `` | Readers see `` \` `` in the block | Same (kept for parity) | Seen in Nomad Network; identical bytes in `pages-ab.sh` |
