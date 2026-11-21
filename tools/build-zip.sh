#!/usr/bin/env bash
set -euo pipefail

(( $# <= 1 )) || { echo "Usage: $0 [git-tag]" >&2; exit 2; }
saved_pwd=$PWD
git_dir=$(git rev-parse --show-toplevel)
cd "$git_dir"
project=$(perl -ne 'if (m/<artifactId>([\w.-]+)<\/artifactId>/) { print $1; exit }' pom.xml)
[[ -n $project ]] || { echo 'Unable to determine project name' >&2; exit 1; }
git_tag=${1:-$(git tag --points-at HEAD)}
[[ -n $git_tag && $git_tag != *$'\n'* ]] || {
  echo 'Specify one release tag (or tag HEAD) before packaging' >&2; exit 1;
}
shopt -s nullglob
jars=(target/"$project"*.jar)
(( ${#jars[@]} == 1 )) || { echo "Expected exactly one target/$project*.jar" >&2; exit 1; }
[[ -r ${jars[0]} ]] || { echo 'Built JAR is not readable' >&2; exit 1; }

# Keep a unique staging directory and replace the output only after success.
workspace=$(mktemp -d "${TMPDIR:-/tmp}/qortal-release.XXXXXX")
archive=$(mktemp -d "$saved_pwd/.qortal-archive.XXXXXX")
trap 'rm -rf -- "$workspace" "$archive"' EXIT
build_dir=$workspace/$project
mkdir "$build_dir"
cp -- "${jars[0]}" "$build_dir/$project.jar"
for file in log4j2.properties start.sh stop.sh; do
  git show "HEAD:$file" > "$build_dir/$file"
done
git show HEAD:tools/qort > "$build_dir/qort"
chmod +x "$build_dir/start.sh" "$build_dir/stop.sh" "$build_dir/qort"
printf '{}\n' > "$build_dir/settings.json"
# POSIX touch -t avoids requiring GNU gtouch and preserves the actual instant.
commit_ts=$(git show --no-patch --format=%ct HEAD)
if timestamp=$(date -u -d "@$commit_ts" +%Y%m%d%H%M.%S 2>/dev/null); then :
else timestamp=$(date -u -r "$commit_ts" +%Y%m%d%H%M.%S); fi
TZ=UTC touch -t "$timestamp" "$build_dir" "$build_dir"/*
(cd "$workspace"; 7z a -r -tzip "$archive/$project.zip" "$project/" -stl)
mv -f -- "$archive/$project.zip" "$saved_pwd/$project.zip"
printf 'Built %s\n' "$saved_pwd/$project.zip"
