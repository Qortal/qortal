# Editing APP/WEBSITE metadata without re-uploading

The new authenticated endpoints build a transaction that changes the title,
description, tags or category of an existing APP or WEBSITE. The caller does
not need to supply the zip or the original files:

- `POST /arbitrary/{service}/{name}/metadata`
- `POST /arbitrary/{service}/{name}/{identifier}/metadata`

Query parameters: `title`, `description`, `tags` (repeatable), `category`,
`fee`. There is no request body. Like the other publish endpoints, the response
is a raw, unsigned ARBITRARY transaction encoded in Base58. The client computes
the nonce (or calls `POST /arbitrary/compute`), signs it and broadcasts it in
the usual way.

## How it works

Title, description, tags and category are not stored on chain. They live in
the off-chain `metadata.json` that each transaction references through
`metadataHash`, alongside the chunk list, file list and MIME type. Nodes show
the metadata of the latest transaction for a name/service/identifier, and
`ArbitraryTransaction.isValid()` does not require a unique data hash. A new PUT
for the same content with a new metadata file is therefore a valid way to
update metadata. **No consensus, protocol or database change is involved.**

`ArbitraryDataMetadataEditor` (`org.qortal.arbitrary`) does the following:

1. Looks up the latest transaction for the resource. If none exists, the
   request is rejected.
2. Reads the current metadata and merges in the supplied fields. Any field that
   is missing or empty keeps its current value.
3. Rejects the request if no metadata would remain, or if nothing changed.
4. Rebuilds the latest state with `ArbitraryDataReader` (any PATCH layers are
   merged).
5. Copies the build, without the internal `.qortal` folder, into
   `<tempDataPath>/metadata-edit/`. The copy is required because
   `ArbitraryDataWriter` deletes its input folder when that folder is inside
   the temp path, so passing the reader's cached build directly would wipe the
   node's cache. The copy is deleted after use.
6. Republishes the copy with `ArbitraryDataTransactionBuilder` as a PUT with
   the merged metadata.

The endpoints only accept APP and WEBSITE, which is what was requested. The
editor itself works for any service, so the API check could be relaxed later.

## Limitations

- **Network cost.** Data files are stored per transaction signature, so every
  other node downloads the full resource again. This is the same cost as a
  normal republish. The edit spares the publisher the zip, not the network
  transfer.
- **Data must be on the node.** The node making the edit must hold the
  resource's data, or be able to fetch it. If the data is missing, the request
  fails and asks the user to load the resource first (for example by opening
  it once).
- **Fields cannot be cleared.** An empty value means "keep the current value".
  Clearing a field would need an explicit flag.
- **The `metadata` identifier is shadowed.** A resource whose identifier is
  literally `metadata` can no longer be published through the path-based
  `POST /{service}/{name}/{identifier}` endpoint, because the literal route
  wins. The existing `/zip`, `/string` and `/base64` routes already behave the
  same way.
- **No UI yet.** Qortal Hub, which is a separate repo, needs an "Edit metadata"
  form that calls these endpoints and then signs and broadcasts as it does
  today.

A possible optimisation for later is a metadata-only PUT that reuses the
previous transaction's data hash, secret, size and chunks. This would skip
re-zipping and re-encryption. It would not reduce network transfer, because
storage is per signature. It would also need extra handling for the chunk
files and does not work when the latest transaction is a PATCH, so it was not
implemented.

## Tests

    mvn -DskipJUnitTests=false -Dtest=ArbitraryMetadataEditTests test

The default Maven configuration skips JUnit. `ArbitraryMetadataEditTests` (8
tests) covers:

- editing all fields;
- a partial edit that keeps the existing values;
- adding metadata to a resource that had none;
- resources with an identifier;
- repeated edits;
- rejection of unchanged metadata, missing metadata and a missing resource.

After each successful edit, the test checks that the rebuilt content has the
same digest as the original files.

At the time of writing, `ArbitraryTransactionMetadataTests` (3 tests) and
`ArbitraryDataTests` (6 tests) fail on a clean `develop` tree. These failures
are not related to this change.

## Files

- `src/main/java/org/qortal/arbitrary/ArbitraryDataMetadataEditor.java` (new)
- `src/main/java/org/qortal/api/resource/ArbitraryResource.java`:
  `postMetadata(...)` endpoints and the `editMetadata(...)` helper
- `src/test/java/org/qortal/test/arbitrary/ArbitraryMetadataEditTests.java` (new)
