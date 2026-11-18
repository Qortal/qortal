# rngit Q-App

A reference Q-App for git repositories served by Qortal Core's rngit node:
browse any Qortal name's repositories on QDN, and manage your own.

Plain HTML, CSS and JavaScript, no build step and no dependencies:
`index.html`, `style.css`, `app.js`.

## What it does

- **Browse** (anyone): a name's repositories; per repository the files (with
  its README), file contents (raw link, binary detection), commits with
  paging, a commit's changes and diff, branches and tags, and the `rns://`
  clone URL of the node serving it.
- **Staged pushes** (the name's owner): pushes that a node without the owner's
  key staged (push flow 2). *Publish* fetches the prepared bundle and
  descriptor from Core and publishes them with
  `qortalRequest({ action: "PUBLISH_MULTIPLE_QDN_RESOURCES", ... })`.
- **New repository** (the name's owner): publishes an empty descriptor with
  `PUBLISH_QDN_RESOURCE`, making the given RNS identity (prefilled from the
  account's published binding) admin, so the owner can push with git. No
  node with a publisher key is needed.
- **Settings** (the name's owner): edit the repository's rules and description;
  republishes the current descriptor with only those fields changed.
- **Identities**: the RNS identities an account has bound with
  `rns-identity-binding.py`.

Reading uses Core's `/git` REST API directly; everything signed or paid goes
through `qortalRequest()`, so Hub asks the user to approve it.

## Requirements

The Core node Hub talks to must run rngit (`"rngitEnabled": true` in
`settings.json`), from `reticulum-git` at or after the commit that added
`/git/{group}/{repo}/descriptor`. Without it every page shows
*rngit is not enabled on this node*.

## Core endpoints used

| Endpoint | Used for |
|---|---|
| `GET /git/{group}` | repository list |
| `GET /git/{group}/{repo}` | refs, HEAD, clone URL, QDN details |
| `GET /git/{group}/{repo}/tree?ref=&path=` | directory listing |
| `GET /git/{group}/{repo}/blob?ref=&path=` | file contents |
| `GET /git/{group}/{repo}/log?ref=&offset=&limit=` | commits |
| `GET /git/{group}/{repo}/commit/{sha}?diff=true` | commit details |
| `GET /git/{group}/{repo}/descriptor` | current descriptor, for owner edits |
| `GET /git/{group}/{repo}/staged` | staged pushes |
| `GET /git/{group}/{repo}/staged/{id}/publish` | resources to publish a staged push |
| `GET /git/identity/{address}` | bound RNS identities |
| `GET /names/{name}` | whether the signed-in account owns a name |

## Publishing the Q-App

Publish the three files as a QDN `APP` (or `WEBSITE`) resource under a name
you own, e.g. from Hub's publish screen with this directory zipped, or
through Core's API (`POST /arbitrary/APP/<name>/zip`, then sign and process).

## Development

`GitApiDevServer` (in Core's test sources) serves the app and answers
`/git/*` through Core's real `GitResource` over a sample repository, so the app
sees exactly the JSON Core produces:

```bash
cd ~/git/qortal-claude
mvn -o -q test-compile -DskipJUnitTests=true
mvn -o -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=/tmp/cp.txt
java -cp "$(cat /tmp/cp.txt):target/classes:target/test-classes" \
     org.qortal.api.resource.GitApiDevServer reticulum/qapp/rngit 42530 --qdn-demo --hub-shim
# open http://127.0.0.1:42530/#/r/demo/hello
```

- `--qdn-demo` presents the sample as a QDN repository (clone URL, a staged
  push, a descriptor), so the Staged and Settings tabs have content.
- `--hub-shim` stands in for Hub: `qortalRequest` signs in as the owner of
  `demo` and logs publish requests to the browser console instead of sending
  them.

Every page has its own URL (`#/g/<name>`, `#/r/<name>/<repo>/tree/<ref>/<path>`,
`.../blob/...`, `.../commits/<ref>`, `.../commit/<sha>`, `.../refs`,
`.../staged`, `.../settings`, `#/new/<name>`, `#/id/<address>`).
