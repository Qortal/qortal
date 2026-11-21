#!/usr/bin/env bash

set -euo pipefail

# === Configurable Defaults ===
BASE_BRANCH="master"
DEFAULT_LOG_DIR="${HOME}/qortal-auto-update-logs"
LOG_FILE=""
DRY_RUN=false
RUN_PUBLISH=false
PUBLISH_SCRIPT="tools/auto-update-scripts/publish-auto-update.py"

# === Helper Functions ===
function abort() {
  echo -e "\nERROR: $1" >&2
  exit 1
}

function confirm_or_exit() {
  echo "$1"
  read -rp "Continue? (y/N): " confirm
  [[ "${confirm}" =~ ^[Yy]$ ]] || exit 1
}

function run_git() {
  echo "Running: git $*" | tee -a "$LOG_FILE"
  $DRY_RUN || git "$@"
}

function increment_version() {
  local version=$1
  local major minor patch
  IFS='.' read -r major minor patch <<< "$version"
  patch=$((10#$patch + 1))
  echo "$major.$minor.$patch"
}

# === Prompt for Logging Directory ===
echo "Default log directory: ${DEFAULT_LOG_DIR}"
read -rp "Use this log directory? (Y/n): " log_choice
if [[ "${log_choice}" =~ ^[Nn]$ ]]; then
  read -rp "Enter desired log directory path: " CUSTOM_LOG_DIR
  LOG_DIR="${CUSTOM_LOG_DIR}"
else
  LOG_DIR="${DEFAULT_LOG_DIR}"
fi

mkdir -p "${LOG_DIR}" || abort "Unable to create log directory: ${LOG_DIR}"
LOG_FILE="${LOG_DIR}/qortal-auto-update-log-$(date +%Y%m%d-%H%M%S).log"
echo "Logging to: ${LOG_FILE}"

# Log everything to file as well as terminal
exec > >(tee -a "$LOG_FILE") 2>&1

# === Dry Run Mode Option ===
read -rp "Enable dry-run mode? (y/N): " dry_choice
if [[ "${dry_choice}" =~ ^[Yy]$ ]]; then
  DRY_RUN=true
  echo "Dry-run mode ENABLED. Commands will be shown but not executed."
else
  echo "Dry-run mode DISABLED. Real commands will be executed."
fi

# === Run Python Publisher Option ===
read -rp "Run the Python publish_auto_update script at the end? (y/N): " pub_choice
if [[ "${pub_choice}" =~ ^[Yy]$ ]]; then
  RUN_PUBLISH=true
  read -rp "Run Python script in dry-run mode? (y/N): " pub_dry
  if [[ "${pub_dry}" =~ ^[Yy]$ ]]; then
    PUBLISH_DRY_FLAG="--dry-run"
  else
    PUBLISH_DRY_FLAG=""
  fi
else
  RUN_PUBLISH=false
fi

# === Detect Git Root ===
git_dir=$(git rev-parse --show-toplevel 2>/dev/null || true)
[[ -z "${git_dir}" ]] && abort "Not inside a git repository."
cd "${git_dir}"

echo
echo "Current Git identity:"
git config user.name || echo "(not set)"
git config user.email || echo "(not set)"

read -rp "Would you like to set/override the Git username and email for this repo? (y/N): " git_id_choice
if [[ "${git_id_choice}" =~ ^[Yy]$ ]]; then
  read -rp "Enter Git username (e.g. Qortal-Auto-Update): " git_user
  read -rp "Enter Git email (e.g. qortal-auto-update@example.com): " git_email

  run_git config user.name "${git_user}"
  run_git config user.email "${git_email}"
  echo "Git identity set to: ${git_user} <${git_email}>"
fi

# === Confirm Git Origin URL ===
git_origin=$(git config --get remote.origin.url)
echo "Git origin URL: ${git_origin}"
confirm_or_exit "Is this the correct repository?"

# === Verify Current Branch ===
current_branch=$(git rev-parse --abbrev-ref HEAD)
echo "Current git branch: ${current_branch}"
if [[ "${current_branch}" != "${BASE_BRANCH}" ]]; then
  echo "Expected to be on '${BASE_BRANCH}' branch, but found '${current_branch}'"
  confirm_or_exit "Proceed anyway in 5 seconds or abort with CTRL+C."
  sleep 5
fi

# === Check for Uncommitted Changes ===
uncommitted=$(git status --short --untracked-files=no)
if [[ -n "${uncommitted}" ]]; then
  echo "Uncommitted changes detected:"
  echo "${uncommitted}"
  abort "Please commit or stash changes first."
fi

project=$(grep -oPm1 "(?<=<artifactId>)[^<]+" pom.xml)
[[ -z "${project}" ]] && abort "Unable to determine project name from pom.xml."
echo "Detected project: ${project}"

# === Auto-Increment Version in pom.xml ===
current_version=$(grep -oPm1 "(?<=<version>)[^<]+" pom.xml)
new_version=$(increment_version "$current_version")

$DRY_RUN || sed -i "s|<version>${current_version}</version>|<version>${new_version}</version>|" pom.xml

echo "Updated version from ${current_version} to ${new_version} in pom.xml"
git diff pom.xml

while true; do
  read -rp "Is the updated version correct? (y/N): " version_ok
  if [[ "${version_ok}" =~ ^[Yy]$ ]]; then
    break
  fi

  read -rp "Enter the correct version number (e.g., 4.7.2): " user_version

  # Validate format x.y.z and version > current_version
  if [[ ! "${user_version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Invalid format. Use x.y.z (e.g., 4.7.2)."
    continue
  fi

  IFS='.' read -r curr_major curr_minor curr_patch <<< "${current_version}"
  IFS='.' read -r new_major new_minor new_patch <<< "${user_version}"

  if (( new_major < curr_major )) || \
     (( new_major == curr_major && new_minor < curr_minor )) || \
     (( new_major == curr_major && new_minor == curr_minor && new_patch <= curr_patch )); then
    echo "Version must be greater than current version (${current_version})."
    continue
  fi

  $DRY_RUN || sed -i "s|<version>${new_version}</version>|<version>${user_version}</version>|" pom.xml
  echo "Updated version to user-provided version: ${user_version}"
  git diff pom.xml
  new_version="${user_version}"
  echo
  echo "Rechecking updated version..."
done

run_git add pom.xml
run_git commit -m "Bump version to ${new_version}"
run_git tag "v${new_version}"

# === Extract Info ===
short_hash=$(git rev-parse --short HEAD)
[[ -z "${short_hash}" ]] && abort "Unable to extract commit hash."
echo "Using commit hash: ${short_hash}"


# === Build JAR ===
echo "Building JAR for ${project}..."
if ! $DRY_RUN; then
  mvn clean package > "${LOG_FILE}.maven" 2>&1 || {
    echo "Build failed. Check logs in ${LOG_FILE}.maven" >&2
    abort "Maven build failed."
  }
fi

if ! $DRY_RUN; then
  shopt -s nullglob
  jars=(target/"$project"*.jar)
  (( ${#jars[@]} == 1 )) || abort 'Expected exactly one packaged JAR.'
  jar_file=${jars[0]}
fi

# === XOR Obfuscation ===
echo "Creating ${project}.update..."
$DRY_RUN || java -cp "${jar_file}" org.qortal.XorUpdate "${jar_file}" "${project}.update"

# === Create Auto-Update Branch ===
update_branch="auto-update-${short_hash}"

echo "Creating update branch: ${update_branch}"
if ! $DRY_RUN; then
  bash tools/create-auto-update-branch.sh "${project}.update" "${update_branch}" \
    "XORed auto-update JAR for commit ${short_hash}"
else
  echo "Would create ${update_branch} containing only ${project}.update; source checkout unchanged."
fi

# Publish refs only after the JAR and update commit have been built successfully.
confirm_or_exit "About to push version tag 'v${new_version}' to origin."
run_git push origin "v${new_version}"
confirm_or_exit "Also push the ${current_branch} branch to origin?"
run_git push origin "${current_branch}"
confirm_or_exit "About to push auto-update branch '${update_branch}' to origin."
run_git push --set-upstream origin "${update_branch}"

# The update commit uses a temporary index; the source branch stays checked out.
if $DRY_RUN; then
  echo 'Dry run complete; no version, tag, update branch, or push was created.'
else
  echo "Done. ${project}.update is committed to ${update_branch}."
fi

# === Summary Output ===
echo
echo "======================================"
echo "✅ Auto-Update Build Complete!"
echo "--------------------------------------"
echo "Project:           ${project}"
echo "Version:           ${new_version}"
echo "Tag:               v${new_version}"
echo "Commit Hash:       ${short_hash}"
echo "Auto-Update Branch: auto-update-${short_hash}"
echo
if $DRY_RUN; then echo "Would push to:     ${git_origin}"
else echo "Pushed to:         ${git_origin}"; fi
echo "Logs saved to:     ${LOG_FILE}"
echo "======================================"
echo
# === Provide additional information regarding publish script, and private key. ===
if $RUN_PUBLISH; then
  echo "...===...===...===...===...===..."
  echo 
  echo "CONTINUING TO EXECUTE PUBLISH SCRIPT AS SELECTED"
  echo
  echo "This will publish the AUTO-UPDATE TRANSACTION for signing by the DEVELOPER GROUP ADMINS"
  echo 
  echo "NOTICE: For security, when prompted for PRIVATE KEY, you will NOT see the input, SIMPLY PASTE/TYPE KEY AND PUSH ENTER."
  echo
  echo "...===...===...===...===...===..."
fi

# === Optionally Run Python Publisher ===
if $RUN_PUBLISH; then
  echo "Running Python publish_auto_update script..."
  if [[ -f "${PUBLISH_SCRIPT}" ]]; then
    if $DRY_RUN; then
      echo 'Dry run: publisher skipped because the proposed update was not built or pushed.'
    elif [[ "${PUBLISH_DRY_FLAG}" == "--dry-run" ]]; then
      python3 "${PUBLISH_SCRIPT}" "${short_hash}" --dry-run
    else
      echo "Publishing auto-update for real..."
      python3 "${PUBLISH_SCRIPT}" "${short_hash}"
    fi
  else
    echo "WARNING: Python script not found at ${PUBLISH_SCRIPT}. Skipping."
  fi
fi
