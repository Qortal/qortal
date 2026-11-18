#!/usr/bin/env python3
#
# A minimal Nomad Network client for run.sh: links to the page node of the
# rngit node with the given repositories destination, as Nomad Network's
# browser does, and makes the requests in a JSON list, printing one JSON line
# per response: {"path", "ok", "text" | "file", "name"}.
#
# Usage: pages_client.py <rns-config> <repositories-dest> <requests.json> [identity-file]

import json
import sys
import time

import RNS


def wait(condition, timeout, what):
    deadline = time.time() + timeout
    while not condition():
        if time.time() > deadline: sys.exit(f"timed out waiting for {what}")
        time.sleep(0.2)


def main():
    configdir, dest_hex, requests_file = sys.argv[1:4]
    identity_file = sys.argv[4] if len(sys.argv) > 4 else None
    RNS.Reticulum(configdir)

    repositories = bytes.fromhex(dest_hex)
    if not RNS.Transport.has_path(repositories): RNS.Transport.request_path(repositories)
    wait(lambda: RNS.Identity.recall(repositories) is not None, 60, "the node's identity")
    identity = RNS.Identity.recall(repositories)

    destination = RNS.Destination(identity, RNS.Destination.OUT, RNS.Destination.SINGLE, "nomadnetwork", "node")
    if not RNS.Transport.has_path(destination.hash): RNS.Transport.request_path(destination.hash)
    wait(lambda: RNS.Transport.has_path(destination.hash), 90, "a path to the page node")
    print(json.dumps({"page_node": destination.hash.hex()}), flush=True)

    link = RNS.Link(destination)
    wait(lambda: link.status == RNS.Link.ACTIVE, 30, "the link")
    if identity_file: link.identify(RNS.Identity.from_file(identity_file))

    with open(requests_file) as fh: requests = json.load(fh)
    for request in requests:
        done = {}

        def received(receipt):
            # A file response is an open file only while the callback runs
            response = receipt.response
            if hasattr(response, "read"): response = response.read()
            done.update(r=(response, receipt.metadata))

        link.request(request["path"], request.get("data"), response_callback=received,
                     failed_callback=lambda r: done.update(failed=True), timeout=60)
        wait(lambda: done, 120, request["path"])
        out = {"path": request["path"], "ok": "r" in done}
        if "r" in done:
            response, metadata = done["r"]
            if metadata and "name" in metadata:
                out["name"] = metadata["name"].decode()
                out["file"] = response.hex()
            else:
                out["text"] = response.decode("utf-8") if isinstance(response, bytes) else repr(response)
        print(json.dumps(out), flush=True)
    link.teardown()


if __name__ == "__main__":
    main()
