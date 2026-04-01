#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
# Qortal Release Helper
# - Builds a changelog from GitHub compare between previous tag and current tag
# - Escapes @ in commit messages to avoid accidental mentions (e.g. @Override)
# - Intentionally @mentions real contributor logins in a dedicated section
# - Packages qortal/ directory into qortal.zip and computes hashes
#
# Usage:
#   ./release.sh 6.1.0
#
# Optional env:
#   GH_TOKEN=...     # strongly recommended to avoid GitHub API rate limits
#   REPO=Qortal/qortal
#   BRANCH=master
#   WORKING_QORTAL_DIR=./qortal
# -----------------------------------------------------------------------------

VERSION="${1:-}"
if [[ -z "${VERSION}" ]]; then
  echo "Usage: $0 <VERSION>"
  exit 1
fi

REPO="${REPO:-Qortal/qortal}"
BRANCH="${BRANCH:-master}"
WORKING_QORTAL_DIR="${WORKING_QORTAL_DIR:-./qortal}"

TAG="v${VERSION}"

# ---- deps -------------------------------------------------------------------
need_cmd() { command -v "$1" >/dev/null 2>&1 || { echo "Missing dependency: $1"; exit 1; }; }
for c in bash curl jq git sed awk sort find touch md5sum sha1sum sha256sum 7z; do need_cmd "$c"; done

# ---- tmp workspace ----------------------------------------------------------
TMPDIR="$(mktemp -d)"
cleanup() { rm -rf "$TMPDIR"; }
trap cleanup EXIT

# ---- github api helpers -----------------------------------------------------
gh_api() {
  local url="$1"
  if [[ -n "${GH_TOKEN:-}" ]]; then
    curl -fsSL \
      -H "Accept: application/vnd.github+json" \
      -H "Authorization: Bearer ${GH_TOKEN}" \
      -H "X-GitHub-Api-Version: 2022-11-28" \
      "$url"
  else
    curl -fsSL \
      -H "Accept: application/vnd.github+json" \
      -H "X-GitHub-Api-Version: 2022-11-28" \
      "$url"
  fi
}

# ---- find previous tag reliably --------------------------------------------
# Uses remote tags and semver-ish sorting via sort -V.
# Works if tags are like v6.1.0, v6.0.9, etc.
get_prev_tag() {
  local repo_url="https://github.com/${REPO}.git"
  local tags_file="${TMPDIR}/tags.txt"

  git ls-remote --tags --refs "$repo_url" 'v*' \
    | awk -F'/' '{print $NF}' \
    | sed 's/\^{}$//' \
    | sort -V > "$tags_file"

  if ! grep -qx "$TAG" "$tags_file"; then
    echo "Error: tag '$TAG' not found in remote tags for ${REPO}."
    echo "       Make sure you pushed the tag first."
    exit 1
  fi

  # previous tag = greatest tag < current tag (sort -V already)
  local prev
  prev="$(awk -v cur="$TAG" '
    $0 == cur { print last; exit }
    { last=$0 }
  ' "$tags_file")"

  if [[ -z "$prev" ]]; then
    echo "Error: Could not determine previous tag before '$TAG' (is this the first tag?)."
    exit 1
  fi

  printf "%s" "$prev"
}

PREV_TAG="$(get_prev_tag)"
COMPARE_URL="https://api.github.com/repos/${REPO}/compare/${PREV_TAG}...${TAG}"

echo "Using compare range: ${PREV_TAG}...${TAG}"
echo "Compare API: ${COMPARE_URL}"

COMPARE_JSON="$(gh_api "$COMPARE_URL")"

# If compare fails, GitHub usually returns a message + status
STATUS="$(echo "$COMPARE_JSON" | jq -r '.status // empty' || true)"
if [[ "${STATUS}" != "ahead" && "${STATUS}" != "identical" && -n "${STATUS}" ]]; then
  echo "Warning: compare status is '${STATUS}'. Proceeding anyway."
fi

# ---- contributors (real GitHub logins) -------------------------------------
# We intentionally @mention these, deduped. Only uses commits that GitHub can map to accounts.
CONTRIB_MENTIONS="$(
  echo "$COMPARE_JSON" \
    | jq -r '.commits[].author.login? // empty' \
    | sort -u \
    | awk '{print "@"$0}' \
    | paste -sd ' ' -
)"
if [[ -z "$CONTRIB_MENTIONS" ]]; then
  # fallback: no logins mapped. Don't make up @mentions.
  CONTRIB_MENTIONS="(No GitHub logins mapped in this range — commits may be unlinked to accounts.)"
fi

# ---- changelog formatting ---------------------------------------------------
# We list commits with:
# - Title
# - short sha link
# - (optional) author login (not @mentioned here; we avoid accidental mentions in log text)
#
# Critical: escape all @ in commit titles/bodies so Java annotations or emails don't become mentions.
#
escape_at() { sed 's/@/\\@/g'; }

CHANGELOG_MD="$(
  echo "$COMPARE_JSON" | jq -r '
    .commits[]
    | .sha as $sha
    | (.sha[0:7]) as $short
    | (.commit.message | split("\n")) as $lines
    | ($lines[0]) as $title
    | (.author.login? // "") as $login
    | "- " + $title
      + "\n  - " + "[" + $short + "](https://github.com/'"${REPO}"'/commit/" + $sha + ")"
      + (if $login != "" then " (author: " + $login + ")" else "" end)
  ' | escape_at
)"

# ---- latest commit timestamp for file timestamping --------------------------
LATEST_COMMIT_TS="$(
  gh_api "https://api.github.com/repos/${REPO}/commits?sha=${BRANCH}&per_page=1" \
    | jq -r '.[0].commit.committer.date'
)"
if [[ -z "$LATEST_COMMIT_TS" || "$LATEST_COMMIT_TS" == "null" ]]; then
  echo "Error: unable to fetch latest commit timestamp"
  exit 1
fi

# ---- ensure working dir / artifacts ----------------------------------------
if [[ ! -d "$WORKING_QORTAL_DIR" ]]; then
  echo "Error: working directory '${WORKING_QORTAL_DIR}' not found."
  read -r -p "Would you like to: (1) Create it here, or (2) Specify a full path? [1/2]: " choice
  if [[ "$choice" == "1" ]]; then
    mkdir -p "$WORKING_QORTAL_DIR"
    echo "Created: ${WORKING_QORTAL_DIR}"
  elif [[ "$choice" == "2" ]]; then
    read -r -p "Enter full path to working directory: " new_path
    if [[ -z "$new_path" ]]; then
      echo "Error: empty path provided. Exiting."
      exit 1
    fi
    WORKING_QORTAL_DIR="$new_path"
    mkdir -p "$WORKING_QORTAL_DIR"
    echo "Using: ${WORKING_QORTAL_DIR}"
  else
    echo "Invalid choice. Exiting."
    exit 1
  fi
fi

mkdir -p "$WORKING_QORTAL_DIR"

JAR_FILE="${WORKING_QORTAL_DIR}/qortal.jar"
if [[ ! -f "$JAR_FILE" ]]; then
  echo "Error: ${JAR_FILE} not found."
  read -r -p "Would you like to: (1) Compile from source, (2) Copy from running core, or (3) Specify a local path? [1/2/3]: " choice

  if [[ "$choice" == "1" ]]; then
    need_cmd mvn
    BUILD_DIR="${TMPDIR}/qortal-build"
    echo "Cloning ${REPO} (${BRANCH}) and compiling..."
    git clone --depth 1 --branch "$BRANCH" "https://github.com/${REPO}.git" "$BUILD_DIR"
    (
      cd "$BUILD_DIR"
      mvn clean package
    )
    BUILT_JAR="$(find "${BUILD_DIR}/target" -maxdepth 1 -type f -name 'qortal-*.jar' | sort | tail -n1 || true)"
    if [[ -z "$BUILT_JAR" || ! -f "$BUILT_JAR" ]]; then
      echo "Error: compile completed but no target/qortal-*.jar was found."
      exit 1
    fi
    cp "$BUILT_JAR" "$JAR_FILE"
    echo "Copied compiled jar to ${JAR_FILE}"
  elif [[ "$choice" == "2" ]]; then
    RUNNING_JAR="${HOME}/qortal/qortal.jar"
    if [[ -f "$RUNNING_JAR" ]]; then
      cp "$RUNNING_JAR" "$JAR_FILE"
      echo "Copied from ${RUNNING_JAR}"
    else
      echo "Error: ${RUNNING_JAR} not found."
      exit 1
    fi
  elif [[ "$choice" == "3" ]]; then
    read -r -p "Enter full path to qortal.jar: " jar_path
    if [[ -z "$jar_path" || ! -f "$jar_path" ]]; then
      echo "Error: invalid path '${jar_path}'. Exiting."
      exit 1
    fi
    cp "$jar_path" "$JAR_FILE"
    echo "Copied from ${jar_path}"
  else
    echo "Invalid choice. Exiting."
    exit 1
  fi
fi

download_required_file() {
  local file="$1"
  local target_path="${WORKING_QORTAL_DIR}/${file}"
  local remote_path="$file"

  if [[ "$file" == "settings.json" ]]; then
    cat > "$target_path" <<'EOF'
{
  "balanceRecorderEnabled": true,
  "apiWhitelistEnabled": false,
  "allowConnectionsWithOlderPeerVersions": false,
  "apiRestricted": false
}
EOF
    return
  fi

  if [[ "$file" == "qort" ]]; then
    remote_path="tools/${file}"
  fi

  curl -fsSL "https://raw.githubusercontent.com/${REPO}/refs/heads/${BRANCH}/${remote_path}" -o "$target_path"
  if [[ "$file" == "qort" || "$file" == "start.sh" || "$file" == "stop.sh" ]]; then
    chmod +x "$target_path"
  fi
}

REQUIRED_FILES=("settings.json" "log4j2.properties" "start.sh" "stop.sh" "qort")
for file in "${REQUIRED_FILES[@]}"; do
  if [[ ! -f "${WORKING_QORTAL_DIR}/${file}" ]]; then
    echo "Error: missing ${WORKING_QORTAL_DIR}/${file}"
    read -r -p "Would you like to: (1) Get this file from GitHub, or (2) Exit and copy manually? [1/2]: " choice
    if [[ "$choice" == "1" ]]; then
      download_required_file "$file"
      echo "Added ${file}"
    elif [[ "$choice" == "2" ]]; then
      echo "Copy files manually into ${WORKING_QORTAL_DIR}, then re-run."
      exit 1
    else
      echo "Invalid choice. Exiting."
      exit 1
    fi
  fi
done

# ---- hash helper ------------------------------------------------------------
calculate_hashes() {
  local file="$1"
  [[ -f "$file" ]] || { echo "Error: file not found: $file"; exit 1; }
  MD5="$(md5sum "$file" | awk '{print $1}')"
  SHA1="$(sha1sum "$file" | awk '{print $1}')"
  SHA256="$(sha256sum "$file" | awk '{print $1}')"
}

# ---- hashes for jar / exe / zip --------------------------------------------
calculate_hashes "$JAR_FILE"
JAR_MD5="$MD5"; JAR_SHA1="$SHA1"; JAR_SHA256="$SHA256"

EXE_FILE="./qortal.exe"
if [[ -f "$EXE_FILE" ]]; then
  calculate_hashes "$EXE_FILE"
  EXE_MD5="$MD5"; EXE_SHA1="$SHA1"; EXE_SHA256="$SHA256"
else
  EXE_MD5="<INPUT>"; EXE_SHA1="<INPUT>"; EXE_SHA256="<INPUT>"
fi

# ---- apply commit timestamp to files in working dir -------------------------
echo "Applying commit timestamp (${LATEST_COMMIT_TS}) to files in ${WORKING_QORTAL_DIR}..."
# keep qortal.exe out of the directory while timestamping if present
if [[ -f "$EXE_FILE" ]]; then
  mv -f "$EXE_FILE" "${WORKING_QORTAL_DIR}/" || true
fi
find "$WORKING_QORTAL_DIR" -type f -exec touch -d "$LATEST_COMMIT_TS" {} \;
if [[ -f "${WORKING_QORTAL_DIR}/qortal.exe" ]]; then
  mv -f "${WORKING_QORTAL_DIR}/qortal.exe" "$EXE_FILE" || true
fi

# ---- create zip -------------------------------------------------------------
ZIP_FILE="./qortal.zip"
echo "Packing ${ZIP_FILE}..."
rm -f "$ZIP_FILE"
7z a -r -tzip "$ZIP_FILE" "${WORKING_QORTAL_DIR}/" -stl

calculate_hashes "$ZIP_FILE"
ZIP_MD5="$MD5"; ZIP_SHA1="$SHA1"; ZIP_SHA256="$SHA256"

# ---- release notes ----------------------------------------------------------
# - Changelog section is @-escaped to prevent accidental mentions
# - Contributors section intentionally tags real logins
RELEASE_NOTES="release-notes.txt"

cat > "$RELEASE_NOTES" <<EOF
### **_Qortal Core ${TAG}_**

**Compare:** [${PREV_TAG} → ${TAG}](https://github.com/${REPO}/compare/${PREV_TAG}...${TAG})

---

## 🔄 Changes Included in This Release

${CHANGELOG_MD}

---

## 👥 Contributors (tagged)

${CONTRIB_MENTIONS}

---

## 📦 Downloads

### [qortal.jar](https://github.com/${REPO}/releases/download/${TAG}/qortal.jar)

\`MD5: ${JAR_MD5}\`  
\`SHA1: ${JAR_SHA1}\`  
\`SHA256: ${JAR_SHA256}\`

### [qortal.exe](https://github.com/${REPO}/releases/download/${TAG}/qortal.exe)

\`MD5: ${EXE_MD5}\`  
\`SHA1: ${EXE_SHA1}\`  
\`SHA256: ${EXE_SHA256}\`

[VirusTotal report for qortal.exe](https://www.virustotal.com/gui/file/${EXE_SHA256}/detection)

### [qortal.zip](https://github.com/${REPO}/releases/download/${TAG}/qortal.zip)

Bare minimum runtime bundle:
- built \`qortal.jar\`
- \`log4j2.properties\`
- \`start.sh\`
- \`stop.sh\`
- \`qort\` helper script
- \`settings.json\`

All timestamps set to: \`${LATEST_COMMIT_TS}\`  
Packed via: \`7z a -r -tzip qortal.zip ${WORKING_QORTAL_DIR}/\`

\`MD5: ${ZIP_MD5}\`  
\`SHA1: ${ZIP_SHA1}\`  
\`SHA256: ${ZIP_SHA256}\`
EOF

echo "Release notes generated: ${RELEASE_NOTES}"
echo "Range: ${PREV_TAG}...${TAG}"
echo "Contributors: ${CONTRIB_MENTIONS}"
