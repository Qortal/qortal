# Auto Updates

## TL;DR: how-to

* Prepare new release version (see way below for details)
* Assuming you are in git 'master' branch, at HEAD
* Shutdown local node if running
* Build auto-update download: `tools/build-auto-update.sh` - uploads auto-update file into new git branch
* Restart local node
* Publish auto-update transaction using *private key* for **non-admin** member of "dev" group:
	`tools/publish-auto-update.pl non-admin-dev-member-private-key-in-base58`
* Wait for auto-update `ARBITRARY` transaction to be confirmed into a block
* Have "dev" group admins 'approve' auto-update using `tools/approve-auto-update.sh`
	This tool will prompt for *private key* of **admin** of "dev" group
* A minimum number of admins are required for approval, and a minimum number of blocks must pass also.
* Nodes will start to download, and apply, the update over the next 20 minutes or so (see CHECK_INTERVAL in AutoUpdate.java)

## Theory
* Using a specific git commit (e.g. abcdef123) we produce a deterministic JAR with consistent hash.
* To avoid issues with over-eager anti-virus / firewalls we obfuscate JAR using very simplistic XOR-based method.
* Obfuscated JAR is uploaded to various well-known locations, usually including github itself, referenced in settings.
* An `ARBITRARY` transaction is published by a **non-admin** member of the "dev" group (groupID 1) with:
	+ 'service' set to 1
	+ txGroupId set to dev groupID, i.e. 1 
and containing this data:
    + git commit's timestamp in milliseconds (8 bytes)
    + git commit's SHA1 hash (20 bytes)
    + SHA256 hash of *obfuscated* JAR (32 bytes)
* Admins of dev group approve above transaction until it reaches, or exceeds, dev group's approval threshold (e.g. 60%).
* Nodes notice approved transaction and begin auto-update process of:
    + checking transaction's timestamp is greater than node's current build timestamp
    + checking git commit timestamp (in data payload) is greater than node's current build timestamp
    + downloading update (obfuscated JAR) from various locations using git commit SHA1 hash
    + checking downloaded update's SHA256 hash matches hash in transaction's data payload
    + calling ApplyUpdate Java class to shutdown, update and restart node

## Obfuscation method
The same method is used to obfuscate and de-obfuscate:
* XOR each byte of the file with 0x5A

## Typical download locations
The git SHA1 commit hash is used to replace `%s` in various download locations, e.g.:
* https://github.com/Qortal/qortal/raw/%s/qortal.update
* https://raw.githubusercontent.com@151.101.16.133/Qortal/qortal/%s/qortal.update

These locations are part of the org.qortal.settings.Settings class and can be overridden in settings.json like:
```
  "autoUpdateRepos": [
    "http://mirror.qortal.org/auto-updates/%s",
    "https://server.host.name@1.2.3.4/Qortal/%s"
  ]
```
The latter entry is an example where the IP address is provided, bypassing name resolution, for situations where DNS is unreliable or unavailable.

## XOR tool
To help with manual verification of auto-updates, there is a XOR tool included in the Qortal JAR.
It can be used thus:
```
$ java -cp qortal.jar org.qortal.XorUpdate
usage: XorUpdate <input-file> <output-file>
$ java -cp qortal.jar org.qortal.XorUpdate qortal.jar qortal.update
$
```

## Preparing new release version

* Shutdown local node
* Modify `pom.xml` and increase version inside `<version>` tag
* Commit new `pom.xml` and push to github, e.g. `git commit -m 'Bumped to v1.4.2' -- pom.xml; git push`
* Tag this new commit with same version: `git tag v1.4.2`
* Push tag up to github: `git push origin v1.4.2`
