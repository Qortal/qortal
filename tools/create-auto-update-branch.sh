#!/usr/bin/env bash
set -euo pipefail

# Create an orphan update commit with a temporary index, leaving the source
# branch, working files, and real index untouched. Existing branches are kept.
(( $# == 3 )) || { echo "Usage: $0 <update-file> <new-branch> <message>" >&2; exit 2; }
git_dir=$(git rev-parse --show-toplevel)
cd "$git_dir"
file=$1
branch=$2
[[ $file != */* && $file == *.update && -s $file ]] || {
  echo 'Expected a nonempty .update file in the repository root' >&2; exit 1;
}
git check-ref-format "refs/heads/$branch"
if git show-ref --verify --quiet "refs/heads/$branch"; then
  echo "Refusing to overwrite existing branch: $branch" >&2
  exit 1
fi
index=$(mktemp "${TMPDIR:-/tmp}/qortal-update-index.XXXXXX")
trap 'rm -f -- "$index" "$index.lock"' EXIT
export GIT_INDEX_FILE=$index
git read-tree --empty
git add --force -- "$file"
tree=$(git write-tree)
commit=$(git commit-tree "$tree" -m "$3")
git update-ref "refs/heads/$branch" "$commit" ''
printf 'Created %s at %s; source checkout unchanged\n' "$branch" "$commit"
