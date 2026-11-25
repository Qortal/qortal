#!/usr/bin/env bash

set -eo pipefail

# Check we are within a git repo
git_dir=$( git rev-parse --show-toplevel )
if [ -z "${git_dir}" ]; then
	echo "Cannot determine top-level directory for git repo"
	exit 1
fi

# Change to git top-level
cd "${git_dir}"

# Check we are in 'master' branch
branch_name=$( git symbolic-ref -q HEAD || echo )
branch_name=${branch_name##refs/heads/}
echo "Current git branch: ${branch_name}"
if [ "${branch_name}" != "master" ]; then
	echo "Unexpected current branch '${branch_name}' - expecting 'master'"
	echo "CTRL-C within 5 seconds to abort"
	sleep 5
fi

# Extract short-form commit hash
short_hash=$( git rev-parse --short HEAD )
if [ -z "${short_hash}" ]; then
	echo "Unable to extract short-form current commit hash"
	exit 1
fi
echo "HEAD commit is: ${short_hash}"

# Check there are no uncommitted changes
uncommitted=$( git status --short --untracked-files=no )
if [ ! -z "${uncommitted}" ]; then
	echo "Cannot continue due to uncommitted files:"
	echo "${uncommitted}"
	exit 1
fi

# Determine project name
project=$( perl -n -e 'if (m/<artifactId>([\w.-]+)<\/artifactId>/) { print $1; exit }' pom.xml )
if [ -z "${project}" ]; then
	echo "Unable to determine project name from pom.xml?"
	exit 1
fi

# Actually rebuild JAR
echo "Building ${project} JAR..."
if ! mvn clean package >"/tmp/${project}-mvn-build.log" 2>&1; then
	echo "Maven build failed. For details, see /tmp/${project}-mvn-build.log"
	exit 1
fi
shopt -s nullglob
jars=(target/"$project"*.jar)
(( ${#jars[@]} == 1 )) || { echo 'Expected exactly one packaged JAR' >&2; exit 1; }

# Convert packaged JAR to XORed update form
echo "Building ${project}.update..."
if ! java -cp "${jars[0]}" org.qortal.XorUpdate "${jars[0]}" "${project}.update"; then
	echo "Failed to create XORed auto-update JAR"
	exit 1
fi

# Create auto-update branch from this commit
update_branch=auto-update-${short_hash}

bash tools/create-auto-update-branch.sh "${project}.update" "$update_branch" \
  "XORed, auto-update JAR based on commit ${short_hash}"
git push --set-upstream origin "$update_branch"
