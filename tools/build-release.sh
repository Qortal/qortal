#!/usr/bin/env bash

# Change this to where AdvancedInstaller outputs built EXE installers
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WINDOWS_INSTALLER_DIR="${SCRIPT_DIR}/../WindowsInstaller/Qortal-SetupFiles"

set -eo pipefail
shopt -s expand_aliases

# optional git tag?
if [ $# -ge 1 ]; then
	git_tag="$1"
	shift
fi

saved_pwd=$PWD

alias SHA256='(sha256 -q || sha256sum | cut -d" " -f1) 2>/dev/null'

function 3hash {
  local zip_src=$1 md5hash sha1hash sha256hash
  if command -v md5sum >/dev/null; then md5hash=$(md5sum "$zip_src" | awk '{print $1}')
  else md5hash=$(md5 -q "$zip_src"); fi
  if command -v sha1sum >/dev/null; then sha1hash=$(sha1sum "$zip_src" | awk '{print $1}')
  else sha1hash=$(shasum -a 1 "$zip_src" | awk '{print $1}'); fi
  if command -v sha256sum >/dev/null; then sha256hash=$(sha256sum "$zip_src" | awk '{print $1}')
  else sha256hash=$(shasum -a 256 "$zip_src" | awk '{print $1}'); fi
  echo "\`MD5: ${md5hash}\`"
  echo "\`SHA1: ${sha1hash}\`"
  echo "\`SHA256: ${sha256hash}\`"
}

# Check we are within a git repo
git_dir=$( git rev-parse --show-toplevel )
if [ -z "${git_dir}" ]; then
	echo "Cannot determine top-level directory for git repo"
	exit 1
fi

# Change to git top-level
cd "${git_dir}"

# Check we are in 'master' branch
# branch_name=$( git symbolic-ref -q HEAD ) || echo "Cannot determine branch name" && exit 1
# branch_name=${branch_name##refs/heads/}
# if [ "${branch_name}" != "master" ]; then
	# echo "Unexpected current branch '${branch_name}' - expecting 'master'"
	# exit 1
# fi

# Determine project name
project=$( perl -n -e 'if (m/<artifactId>([\w.-]+)<\/artifactId>/) { print $1; exit }' pom.xml )
if [ -z "${project}" ]; then
	echo "Unable to determine project name from pom.xml?"
	exit 1
fi

# Extract git tag
if [ -z "${git_tag}" ]; then
	git_tag=$( git tag --points-at HEAD )
	if [ -z "${git_tag}" ]; then
		echo "Unable to extract git tag"
		exit 1
	fi
fi

# Find origin URL
git_url=$( git remote get-url origin )
case $git_url in
  git@github.com:*) git_url=https://github.com/${git_url#git@github.com:} ;;
  ssh://git@github.com/*) git_url=https://github.com/${git_url#ssh://git@github.com/} ;;
  https://github.com/*) ;;
  *) echo 'Expected a GitHub origin for release download links' >&2; exit 1 ;;
esac
git_url=${git_url%.git}
[[ $git_tag != *$'\n'* ]] || { echo 'Specify a single release tag' >&2; exit 1; }
shopt -s nullglob
jars=(target/"$project"*.jar)
(( ${#jars[@]} == 1 )) || { echo 'Expected exactly one packaged JAR' >&2; exit 1; }

# Check for EXE
exe=${project}.exe
exe_src="${WINDOWS_INSTALLER_DIR}/${exe}"
if [ ! -r "${exe_src}" ]; then
	echo "Cannot find EXE installer at ${exe_src}"
	exit 1
fi

# Check for ZIP
zip_filename=${project}.zip
zip_src=${saved_pwd}/${zip_filename}
if [ ! -r "${zip_src}" ]; then
	echo "Cannot find ZIP at ${zip_src}"
	exit 1
fi



# Changes
cat <<"__CHANGES__"
*Changes in this release:*
* 
__CHANGES__

# JAR
cat <<__JAR__

### [${project}.jar](${git_url}/releases/download/${git_tag}/${project}.jar)

__JAR__
3hash "${jars[0]}"

# EXE
cat <<__EXE__

### [${exe}](${git_url}/releases/download/${git_tag}/${exe})

__EXE__
3hash "${exe_src}"

# VirusTotal url is SHA256 of github download url:
virustotal_url=$( echo -n "${git_url}/releases/download/${git_tag}/${exe}" | SHA256 )
cat <<__VIRUSTOTAL__

[VirusTotal report for ${exe}](https://www.virustotal.com/gui/url/${virustotal_url}/detection)
__VIRUSTOTAL__

# ZIP
cat <<__ZIP__

### [${zip_filename}](${git_url}/releases/download/${git_tag}/${zip_filename})

Contains bare minimum of:
* built \`${project}.jar\`
* \`log4j2.properties\` from git repo
* \`start.sh\` from git repo
* \`stop.sh\` from git repo
* \`printf "{\n}\n" > settings.json\`

All timestamps set to same date-time as commit, obtained via \`git show --no-patch --format=%cI\`
Packed with \`7z a -r -tzip ${zip_filename} ${project}/\`

__ZIP__
3hash "${zip_src}"
