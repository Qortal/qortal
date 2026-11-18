// rngit: a reference Q-App for git repositories served by Qortal Core's rngit
// node. It reads through Core's /git REST API and uses qortalRequest() for
// everything that needs the user's key (creating repositories, editing rules,
// publishing staged pushes). No build step: plain JavaScript, no dependencies.
"use strict";

const SERVICE = "GIT_REPOSITORY";
const DESCRIPTOR_FORMAT = "rngit-qdn/1";
const MAX_REPOSITORY_NAME = 48;
const LOG_PAGE = 30;

const view = document.getElementById("view");
const crumbs = document.getElementById("crumbs");
const accountBadge = document.getElementById("account");

// ----------------------------------------------------------------------
// Small DOM helpers. Everything user- or network-supplied goes in as text.

function el(tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === undefined || value === null || value === false) continue;
    if (key === "class") node.className = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children.flat()) {
    if (child === undefined || child === null || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

function show(...nodes) {
  view.replaceChildren(...nodes);
}

function setCrumbs(parts) {
  const items = [el("a", { href: "#/" }, "home")];
  for (const [label, href] of parts) {
    items.push(" / ", href ? el("a", { href }, label) : el("span", {}, label));
  }
  crumbs.replaceChildren(...items);
}

function errorBox(message) {
  return el("div", { class: "error" }, message);
}

function enc(segment) {
  return encodeURIComponent(segment);
}

function shortSha(sha) {
  return sha ? sha.slice(0, 10) : "";
}

function when(ms) {
  if (!ms) return "";
  return new Date(ms).toLocaleString();
}

function bytes(n) {
  if (n === undefined || n === null) return "";
  if (n < 1024) return n + " B";
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KiB";
  return (n / 1024 / 1024).toFixed(1) + " MiB";
}

function toBase64(text) {
  const utf8 = new TextEncoder().encode(text);
  let binary = "";
  for (const b of utf8) binary += String.fromCharCode(b);
  return btoa(binary);
}

// ----------------------------------------------------------------------
// Core API and qortalRequest

async function api(path, options) {
  const response = await fetch(path, options);
  if (!response.ok) {
    let message = response.status + " " + response.statusText;
    try {
      const body = await response.json();
      if (body && body.message) message = body.message;
    } catch (e) { /* not JSON */ }
    throw new Error(message);
  }
  return response;
}

async function apiJson(path) {
  return (await api(path)).json();
}

function repoPath(group, repo) {
  return "/git/" + enc(group) + "/" + enc(repo);
}

function inHub() {
  return typeof qortalRequest === "function";
}

async function qortal(request) {
  if (!inHub()) throw new Error("This needs Qortal Hub: open the app there to sign with your account.");
  return qortalRequest(request);
}

let account = null;

async function signIn() {
  account = await qortal({ action: "GET_USER_ACCOUNT" });
  accountBadge.textContent = account && account.address ? account.address : "";
  return account;
}

async function ownsName(name) {
  if (!account) return false;
  try {
    const data = await apiJson("/names/" + enc(name));
    return data.owner === account.address;
  } catch (e) {
    return false;
  }
}

// ----------------------------------------------------------------------
// Rules, as rngit's .allowed files: one permission:target per line

const PERMISSIONS = ["r", "read", "w", "write", "rw", "readwrite", "c", "create", "s", "stats",
  "rel", "release", "i", "interact", "p", "propose", "adm", "admin"];

function invalidRule(text) {
  const lines = text.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line || line.startsWith("#")) continue;
    const colon = line.indexOf(":");
    if (colon < 0) return { line: i + 1, text: line };
    const permission = line.slice(0, colon).toLowerCase();
    const target = line.slice(colon + 1);
    const ok = PERMISSIONS.includes(permission) && (
      /^[0-9a-fA-F]{32}$/.test(target) || ["a", "all", "everyone", "n", "none", "nobody", "owner"].includes(target) ||
      /^name:[^:]+$/.test(target) || /^group:\d{1,9}$/.test(target));
    if (!ok) return { line: i + 1, text: line };
  }
  return null;
}

// ----------------------------------------------------------------------
// Views

async function homeView() {
  setCrumbs([]);
  const nameInput = el("input", { placeholder: "Qortal name", "aria-label": "Qortal name" });
  const addressInput = el("input", { placeholder: "Qortal address", "aria-label": "Qortal address", class: "mono" });
  const mine = el("div", { class: "body" }, inHub()
    ? el("button", { onclick: async () => { try { await signIn(); route(); } catch (e) { mine.replaceChildren(errorBox(e.message)); } } }, "Show my names")
    : el("span", { class: "muted" }, "Open in Qortal Hub to see your own names."));

  if (account) {
    try {
      const names = await qortal({ action: "GET_ACCOUNT_NAMES", address: account.address });
      mine.replaceChildren(...(names.length ? names.map(n => el("div", {}, el("a", { href: "#/g/" + enc(n.name) }, n.name)))
        : [el("span", { class: "muted" }, "This account owns no names.")]));
    } catch (e) {
      mine.replaceChildren(errorBox(e.message));
    }
  }

  show(
    el("div", { class: "panel" }, el("h2", {}, "Browse a name's repositories"),
      el("div", { class: "body" }, el("form", { class: "clone", onsubmit: e => { e.preventDefault(); if (nameInput.value.trim()) location.hash = "#/g/" + enc(nameInput.value.trim()); } },
        nameInput, el("button", { type: "submit", class: "primary" }, "Open")))),
    el("div", { class: "panel" }, el("h2", {}, "My names"), mine),
    el("div", { class: "panel" }, el("h2", {}, "RNS identities bound to an account"),
      el("div", { class: "body" }, el("form", { class: "clone", onsubmit: e => { e.preventDefault(); if (addressInput.value.trim()) location.hash = "#/id/" + enc(addressInput.value.trim()); } },
        addressInput, el("button", { type: "submit" }, "Look up")))),
  );
}

async function groupView(group) {
  setCrumbs([[group]]);
  show(el("div", { class: "note" }, "Loading…"));
  const owner = await ownsName(group);
  let names;
  try {
    names = await apiJson("/git/" + enc(group));
  } catch (e) {
    show(errorBox(e.message));
    return;
  }
  const list = el("div", { class: "panel" },
    el("div", { class: "head" }, el("strong", {}, group), el("span", { class: "muted" }, names.length + " repositories"),
      owner ? el("a", { href: "#/new/" + enc(group), style: "margin-left:auto" }, "New repository") : null),
    names.length ? names.map(r => el("div", { class: "row" }, el("span", { class: "icon" }, "▣"),
      el("a", { class: "grow", href: "#/r/" + enc(group) + "/" + enc(r) }, r)))
      : el("div", { class: "note" }, "No repositories under this name yet."));
  show(list);
}

async function loadSummary(group, repo) {
  return apiJson(repoPath(group, repo));
}

function repoHeader(group, repo, summary, tab, ref) {
  const cloneInput = el("input", { readonly: true, value: summary.cloneUrl || "", "aria-label": "clone URL" });
  const base = "#/r/" + enc(group) + "/" + enc(repo);
  const refName = ref || (summary.head || "").replace(/^refs\/heads\//, "");
  const tabs = [
    ["code", "Code", base + "/tree/" + enc(refName) + "/"],
    ["commits", "Commits", base + "/commits/" + enc(refName)],
    ["refs", "Branches & tags", base + "/refs"],
  ];
  if (summary.qdn) {
    tabs.push(["staged", "Staged", base + "/staged"]);
    tabs.push(["settings", "Settings", base + "/settings"]);
  }
  return [
    el("div", { class: "panel" },
      el("div", { class: "head" }, el("strong", {}, group + " / " + repo),
        summary.qdn ? el("span", { class: "badge" }, "QDN") : null,
        summary.type ? el("span", { class: "badge" }, summary.type) : null),
      el("div", { class: "body" },
        summary.description ? el("p", {}, summary.description) : null,
        summary.upstream ? el("p", { class: "muted" }, "Upstream: ", el("code", {}, summary.upstream)) : null,
        summary.cloneUrl ? el("div", { class: "clone" }, el("span", { class: "muted" }, "Clone"), cloneInput,
          el("button", { onclick: () => { cloneInput.select(); navigator.clipboard && navigator.clipboard.writeText(cloneInput.value); } }, "Copy"))
          : el("span", { class: "muted" }, "No clone URL: this node does not expose its rngit destination."))),
    el("nav", { class: "tabs" }, tabs.map(([key, label, href]) => el("a", { href, class: key === tab ? "on" : "" }, label))),
  ];
}

function refSelect(group, repo, summary, ref, makeHash) {
  const select = el("select", { "aria-label": "branch or tag", onchange: () => { location.hash = makeHash(select.value); } });
  for (const name of Object.keys(summary.refs || {})) {
    const short = name.replace(/^refs\/(heads|tags)\//, "");
    select.append(el("option", { value: short, selected: short === ref }, short));
  }
  return select;
}

async function treeView(group, repo, ref, path) {
  const summary = await loadSummary(group, repo);
  if (!ref) ref = (summary.head || "refs/heads/master").replace(/^refs\/heads\//, "");
  const segments = path ? path.split("/").filter(Boolean) : [];
  setCrumbs([[group, "#/g/" + enc(group)], [repo, "#/r/" + enc(group) + "/" + enc(repo)], ...segments.map((s, i) =>
    [s, i < segments.length - 1 ? "#/r/" + enc(group) + "/" + enc(repo) + "/tree/" + enc(ref) + "/" + segments.slice(0, i + 1).map(enc).join("/") : null])]);

  if (!summary.refs || Object.keys(summary.refs).length === 0) {
    show(...repoHeader(group, repo, summary, "code", ref),
      el("div", { class: "panel" }, el("div", { class: "note" }, "This repository is empty. Push to it with: git push ", summary.cloneUrl || "<clone URL>", " master")));
    return;
  }

  let entries;
  try {
    entries = await apiJson(repoPath(group, repo) + "/tree?ref=" + enc(ref) + "&path=" + enc(path || ""));
  } catch (e) {
    show(...repoHeader(group, repo, summary, "code", ref), errorBox(e.message));
    return;
  }
  entries.sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name) : a.type === "tree" ? -1 : 1));
  const base = "#/r/" + enc(group) + "/" + enc(repo);
  const rows = entries.map(entry => el("div", { class: "row" },
    el("span", { class: "icon" }, entry.type === "tree" ? "📁" : entry.type === "commit" ? "↗" : "·"),
    entry.type === "commit" ? el("span", { class: "grow" }, entry.name, " ", el("span", { class: "sha" }, "submodule " + shortSha(entry.sha)))
      : el("a", { class: "grow", href: base + (entry.type === "tree" ? "/tree/" : "/blob/") + enc(ref) + "/" + entry.path.split("/").map(enc).join("/") }, entry.name),
    el("span", { class: "muted" }, entry.type === "blob" ? bytes(entry.size) : "")));

  const panels = [...repoHeader(group, repo, summary, "code", ref),
    el("div", { class: "panel" },
      el("div", { class: "head" }, refSelect(group, repo, summary, ref, r => base + "/tree/" + enc(r) + "/" + (path || "")),
        el("span", { class: "muted" }, path ? "/" + path : "/")),
      rows)];

  const readme = entries.find(e => e.type === "blob" && /^readme(\.(md|txt|mu))?$/i.test(e.name));
  if (readme) {
    try {
      const text = await (await api(repoPath(group, repo) + "/blob?ref=" + enc(ref) + "&path=" + enc(readme.path))).text();
      panels.push(el("div", { class: "panel" }, el("h2", {}, readme.name), el("pre", { class: "body code", style: "white-space:pre-wrap" }, text)));
    } catch (e) { /* the listing is still useful without it */ }
  }
  show(...panels);
}

function looksBinary(buffer) {
  const view = new Uint8Array(buffer, 0, Math.min(buffer.byteLength, 8000));
  return view.includes(0);
}

async function blobView(group, repo, ref, path) {
  const summary = await loadSummary(group, repo);
  const segments = path.split("/");
  const base = "#/r/" + enc(group) + "/" + enc(repo);
  setCrumbs([[group, "#/g/" + enc(group)], [repo, base], ...segments.map((s, i) =>
    [s, i < segments.length - 1 ? base + "/tree/" + enc(ref) + "/" + segments.slice(0, i + 1).map(enc).join("/") : null])]);
  const url = repoPath(group, repo) + "/blob?ref=" + enc(ref) + "&path=" + enc(path);
  let body;
  try {
    const buffer = await (await api(url)).arrayBuffer();
    if (looksBinary(buffer)) {
      body = el("div", { class: "note" }, "Binary file, " + bytes(buffer.byteLength) + ". ", el("a", { href: url, download: segments[segments.length - 1] }, "Download"));
    } else {
      const lines = new TextDecoder().decode(buffer).replace(/\n$/, "").split("\n");
      body = el("div", { class: "code" }, el("table", {}, lines.map((line, i) => el("tr", {}, el("td", { class: "n" }, i + 1), el("td", { class: "l" }, line)))));
    }
  } catch (e) {
    body = errorBox(e.message);
  }
  show(...repoHeader(group, repo, summary, "code", ref),
    el("div", { class: "panel" }, el("div", { class: "head" }, el("span", { class: "mono" }, path), el("span", { class: "muted" }, "at " + ref),
      el("a", { href: url, style: "margin-left:auto" }, "Raw")), body));
}

async function commitsView(group, repo, ref, page) {
  const summary = await loadSummary(group, repo);
  const base = "#/r/" + enc(group) + "/" + enc(repo);
  setCrumbs([[group, "#/g/" + enc(group)], [repo, base], ["commits"]]);
  page = Math.max(0, page || 0);
  let commits;
  try {
    commits = await apiJson(repoPath(group, repo) + "/log?ref=" + enc(ref) + "&offset=" + page * LOG_PAGE + "&limit=" + LOG_PAGE);
  } catch (e) {
    show(...repoHeader(group, repo, summary, "commits", ref), errorBox(e.message));
    return;
  }
  const rows = commits.map(c => el("div", { class: "row" },
    el("a", { class: "sha", href: base + "/commit/" + c.sha }, shortSha(c.sha)),
    el("span", { class: "grow" }, c.summary),
    c.signed ? el("span", { class: "badge" }, "signed") : null,
    el("span", { class: "muted" }, c.author.name + ", " + when(c.author.timestamp))));
  show(...repoHeader(group, repo, summary, "commits", ref),
    el("div", { class: "panel" },
      el("div", { class: "head" }, refSelect(group, repo, summary, ref, r => base + "/commits/" + enc(r)),
        el("span", { class: "muted", style: "margin-left:auto" }, "page " + (page + 1)),
        page > 0 ? el("a", { href: base + "/commits/" + enc(ref) + "/" + (page - 1) }, "newer") : null,
        commits.length === LOG_PAGE ? el("a", { href: base + "/commits/" + enc(ref) + "/" + (page + 1) }, "older") : null),
      rows.length ? rows : el("div", { class: "note" }, "No commits.")));
}

function diffBlock(text) {
  const lines = text.replace(/\n$/, "").split("\n");
  return el("div", { class: "code diff" }, el("table", {}, lines.map(line => {
    let cls = "";
    if (line.startsWith("diff --git")) cls = "file";
    else if (line.startsWith("@@")) cls = "hunk";
    else if (line.startsWith("+") && !line.startsWith("+++")) cls = "add";
    else if (line.startsWith("-") && !line.startsWith("---")) cls = "del";
    return el("tr", { class: cls }, el("td", { class: "l" }, line));
  })));
}

async function commitView(group, repo, sha) {
  const summary = await loadSummary(group, repo);
  const base = "#/r/" + enc(group) + "/" + enc(repo);
  setCrumbs([[group, "#/g/" + enc(group)], [repo, base], [shortSha(sha)]]);
  let commit;
  try {
    commit = await apiJson(repoPath(group, repo) + "/commit/" + enc(sha) + "?diff=true");
  } catch (e) {
    show(...repoHeader(group, repo, summary, "commits"), errorBox(e.message));
    return;
  }
  const kinds = { add: "added", delete: "deleted", modify: "modified", rename: "renamed", copy: "copied" };
  show(...repoHeader(group, repo, summary, "commits"),
    el("div", { class: "panel" },
      el("div", { class: "head" }, el("strong", {}, commit.summary), commit.signed ? el("span", { class: "badge" }, "signed") : null),
      el("div", { class: "body" },
        el("pre", { class: "mono", style: "white-space:pre-wrap;margin:0 0 10px" }, commit.message),
        el("div", { class: "muted" }, commit.author.name + " <" + commit.author.email + "> · " + when(commit.author.timestamp)),
        el("div", { class: "sha" }, "commit " + commit.sha),
        commit.parents.map(p => el("div", { class: "sha" }, "parent ", el("a", { href: base + "/commit/" + p }, p))))),
    el("div", { class: "panel" }, el("h2", {}, commit.changes.length + " files changed"),
      commit.changes.map(c => el("div", { class: "row" }, el("span", { class: "badge" }, kinds[c.type] || c.type),
        el("span", { class: "grow mono" }, c.type === "rename" ? c.oldPath + " → " + c.newPath : (c.newPath || c.oldPath))))),
    commit.diff ? el("div", { class: "panel" }, el("h2", {}, "Diff", commit.diffTruncated ? el("span", { class: "badge warn" }, "truncated") : null),
      diffBlock(commit.diff)) : null);
}

async function refsView(group, repo) {
  const summary = await loadSummary(group, repo);
  const base = "#/r/" + enc(group) + "/" + enc(repo);
  setCrumbs([[group, "#/g/" + enc(group)], [repo, base], ["branches & tags"]]);
  const rows = Object.entries(summary.refs || {}).map(([name, sha]) => {
    const short = name.replace(/^refs\/(heads|tags)\//, "");
    const kind = name.startsWith("refs/tags/") ? "tag" : name.startsWith("refs/heads/") ? "branch" : "ref";
    return el("div", { class: "row" }, el("span", { class: "badge" }, kind),
      el("a", { class: "grow", href: base + "/tree/" + enc(short) + "/" }, short),
      name === summary.head ? el("span", { class: "badge ok" }, "HEAD") : null,
      el("a", { class: "sha", href: base + "/commit/" + sha }, shortSha(sha)));
  });
  show(...repoHeader(group, repo, summary, "refs"),
    el("div", { class: "panel" }, rows.length ? rows : el("div", { class: "note" }, "No branches or tags yet.")));
}

async function stagedView(group, repo) {
  const summary = await loadSummary(group, repo);
  const base = "#/r/" + enc(group) + "/" + enc(repo);
  setCrumbs([[group, "#/g/" + enc(group)], [repo, base], ["staged"]]);
  const owner = await ownsName(group);
  let staged;
  try {
    staged = await apiJson(repoPath(group, repo) + "/staged");
  } catch (e) {
    show(...repoHeader(group, repo, summary, "staged"), errorBox(e.message));
    return;
  }
  const status = el("div");
  async function approve(change, button) {
    button.disabled = true;
    status.replaceChildren(el("div", { class: "note" }, "Preparing #" + change.id + "…"));
    try {
      const prepared = await apiJson(repoPath(group, repo) + "/staged/" + change.id + "/publish");
      status.replaceChildren(el("div", { class: "note" }, "Publishing " + prepared.resources.map(r => r.identifier).join(" and ") + " — approve in Hub."));
      await qortal({ action: "PUBLISH_MULTIPLE_QDN_RESOURCES", resources: prepared.resources });
      status.replaceChildren(el("div", { class: "note" }, "Published. Every node shows the change once the transactions are in a block; it then leaves this list."));
    } catch (e) {
      status.replaceChildren(errorBox(e.message));
      button.disabled = false;
    }
  }
  const rows = staged.map(change => {
    const approveButton = el("button", { class: "primary", disabled: !owner || !change.applies }, "Publish");
    approveButton.addEventListener("click", () => approve(change, approveButton));
    return el("div", { class: "row" },
      el("strong", {}, "#" + change.id),
      el("span", { class: "grow" }, change.kind === "delete" ? "delete " + change.ref
        : change.ref.replace(/^refs\/heads\//, "") + ": " + (change.base ? shortSha(change.base) : "(new)") + " → " + shortSha(change.sha),
        change.force ? " (forced)" : ""),
      el("span", { class: change.applies ? "badge ok" : "badge warn" }, change.applies ? "applies" : "conflict"),
      el("span", { class: "sha", title: "pusher" }, shortSha(change.pusher)),
      el("span", { class: "muted" }, when(change.created)),
      approveButton);
  });
  const hint = owner ? null : el("div", { class: "note" }, inHub()
    ? (account ? "Only the owner of " + group + " can publish staged changes." : el("button", { onclick: async () => { await signIn(); route(); } }, "Sign in as the owner to publish"))
    : "Open in Qortal Hub as the owner of " + group + " to publish staged changes.");
  show(...repoHeader(group, repo, summary, "staged"),
    el("div", { class: "panel" }, el("h2", {}, "Pushes staged on this node, awaiting the owner"),
      rows.length ? rows : el("div", { class: "note" }, "Nothing staged on this node."), hint),
    status);
}

async function publishDescriptor(group, descriptor) {
  return qortal({ action: "PUBLISH_QDN_RESOURCE", name: group, service: SERVICE, identifier: descriptor.repository,
    data64: toBase64(JSON.stringify(descriptor, null, 2)), filename: "rngit.json", title: descriptor.repository,
    description: descriptor.description || undefined });
}

async function settingsView(group, repo) {
  const summary = await loadSummary(group, repo);
  const base = "#/r/" + enc(group) + "/" + enc(repo);
  setCrumbs([[group, "#/g/" + enc(group)], [repo, base], ["settings"]]);
  const owner = await ownsName(group);
  let descriptor;
  try {
    descriptor = await apiJson(repoPath(group, repo) + "/descriptor");
  } catch (e) {
    show(...repoHeader(group, repo, summary, "settings"), errorBox(e.message));
    return;
  }
  const rules = el("textarea", { rows: 8, "aria-label": "rules" });
  rules.value = descriptor.allowed || "";
  const description = el("input", { value: descriptor.description || "", "aria-label": "description" });
  const status = el("div");
  const save = el("button", { class: "primary", disabled: !owner, type: "submit" }, "Publish");
  const form = el("form", { class: "form body", onsubmit: async e => {
    e.preventDefault();
    const bad = invalidRule(rules.value);
    if (bad) { status.replaceChildren(errorBox("Invalid rule on line " + bad.line + ": " + bad.text)); return; }
    save.disabled = true;
    try {
      // Re-read right before publishing: refs and bundles must be republished exactly
      const current = await apiJson(repoPath(group, repo) + "/descriptor");
      current.allowed = rules.value;
      current.description = description.value.trim() || undefined;
      await publishDescriptor(group, current);
      status.replaceChildren(el("div", { class: "note" }, "Published. The new rules apply once the transaction is in a block."));
    } catch (err) {
      status.replaceChildren(errorBox(err.message));
    } finally {
      save.disabled = !owner;
    }
  } },
    el("label", {}, "Description", description),
    el("label", {}, "Rules", rules, el("span", { class: "hint" },
      "One permission:target per line, as in rngit .allowed files. Permissions: r, w, rw, c, s, rel, i, p, adm. ",
      "Targets: all, none, an RNS identity hash, name:<Qortal name>, group:<Qortal group id>, owner.")),
    el("div", { class: "actions" }, save));
  show(...repoHeader(group, repo, summary, "settings"),
    el("div", { class: "panel" }, el("h2", {}, "Repository settings"), form,
      owner ? null : el("div", { class: "note" }, "Only the owner of " + group + " can change these.")),
    el("div", { class: "panel" }, el("h2", {}, "Descriptor"),
      el("pre", { class: "body code" }, JSON.stringify({ ...descriptor, refs: Object.keys(descriptor.refs).length + " refs",
        bundles: descriptor.bundles }, null, 2))),
    status);
}

async function newRepositoryView(group) {
  setCrumbs([[group, "#/g/" + enc(group)], ["new repository"]]);
  if (!account && inHub()) {
    try { await signIn(); } catch (e) { /* show the form anyway */ }
  }
  const owner = await ownsName(group);
  const name = el("input", { required: true, maxlength: MAX_REPOSITORY_NAME, pattern: "[^~/]+", "aria-label": "repository name" });
  const identity = el("input", { class: "mono", placeholder: "32 hex characters", "aria-label": "your RNS identity hash" });
  const description = el("input", { "aria-label": "description" });
  const status = el("div");
  if (account) {
    try {
      const bound = await apiJson("/git/identity/" + enc(account.address));
      if (bound.identities.length) identity.value = bound.identities[0];
    } catch (e) { /* type it in */ }
  }
  const create = el("button", { class: "primary", type: "submit", disabled: !owner }, "Create");
  show(el("div", { class: "panel" }, el("h2", {}, "New repository under " + group),
    el("form", { class: "form body", onsubmit: async e => {
      e.preventDefault();
      const repo = name.value.trim();
      const hash = identity.value.trim().toLowerCase();
      if (!repo || repo.length > MAX_REPOSITORY_NAME || /[~/]/.test(repo) || repo === "." || repo === "..") {
        status.replaceChildren(errorBox("Use at most " + MAX_REPOSITORY_NAME + " characters, without ~ or /."));
        return;
      }
      const admin = /^[0-9a-f]{32}$/.test(hash) ? "adm:" + hash : "adm:owner";
      create.disabled = true;
      try {
        await publishDescriptor(group, { format: DESCRIPTOR_FORMAT, repository: repo, head: "refs/heads/master",
          refs: {}, bundles: [], allowed: admin, description: description.value.trim() || undefined });
        status.replaceChildren(el("div", { class: "note" }, "Published. Once it is in a block: ",
          el("a", { href: "#/r/" + enc(group) + "/" + enc(repo) }, group + "/" + repo)));
      } catch (err) {
        status.replaceChildren(errorBox(err.message));
        create.disabled = false;
      }
    } },
      el("label", {}, "Name", name, el("span", { class: "hint" }, "At most " + MAX_REPOSITORY_NAME + " characters, without ~ or /.")),
      el("label", {}, "Your RNS identity hash", identity, el("span", { class: "hint" },
        "Made admin of the repository, so you can push with git. Prefilled from your published binding; empty means adm:owner (whoever owns " + group + ").")),
      el("label", {}, "Description", description),
      el("div", { class: "actions" }, create)),
    owner ? null : el("div", { class: "note" }, "Only the owner of " + group + " can create repositories under it.")),
    status);
}

async function identityView(address) {
  setCrumbs([["identity " + address]]);
  try {
    const bound = await apiJson("/git/identity/" + enc(address));
    show(el("div", { class: "panel" }, el("h2", {}, "RNS identities bound to " + address),
      bound.identities.length ? bound.identities.map(h => el("div", { class: "row mono" }, h))
        : el("div", { class: "note" }, "None. The account publishes them as JSON/<its name>/rns-identity, e.g. with rns-identity-binding.py.")));
  } catch (e) {
    show(errorBox(e.message));
  }
}

// ----------------------------------------------------------------------
// Routing: #/g/<group>, #/r/<group>/<repo>[/tree|blob|commits|commit|refs|staged|settings/...], #/new/<group>, #/id/<address>

async function route() {
  const parts = location.hash.replace(/^#\/?/, "").split("/").map(decodeURIComponent);
  try {
    if (!parts[0]) return await homeView();
    if (parts[0] === "g" && parts[1]) return await groupView(parts[1]);
    if (parts[0] === "new" && parts[1]) return await newRepositoryView(parts[1]);
    if (parts[0] === "id" && parts[1]) return await identityView(parts[1]);
    if (parts[0] === "r" && parts[1] && parts[2]) {
      const [, group, repo, page, ...rest] = parts;
      switch (page) {
        case undefined: case "": case "tree": return await treeView(group, repo, rest[0], rest.slice(1).join("/"));
        case "blob": return await blobView(group, repo, rest[0], rest.slice(1).join("/"));
        case "commits": return await commitsView(group, repo, rest[0], Number(rest[1] || 0));
        case "commit": return await commitView(group, repo, rest[0]);
        case "refs": return await refsView(group, repo);
        case "staged": return await stagedView(group, repo);
        case "settings": return await settingsView(group, repo);
      }
    }
    show(errorBox("Unknown page."));
  } catch (e) {
    show(errorBox(e.message));
  }
}

document.getElementById("goto").addEventListener("submit", e => {
  e.preventDefault();
  const name = document.getElementById("goto-name").value.trim();
  if (name) location.hash = "#/g/" + enc(name);
});
window.addEventListener("hashchange", route);

// Inside Hub, offer sign-in everywhere: owner actions appear once the account is known
if (inHub()) {
  accountBadge.replaceChildren(el("button", { onclick: async () => {
    try { await signIn(); route(); } catch (e) { show(errorBox(e.message)); }
  } }, "Sign in"));
}
route();
