#!/usr/bin/env bash
#
# Runs the rngit tests that need Core's database (the QDN gateway and
# publishing tests) on Core's in-memory test chain.
#
# They cannot run under plain "mvn test": Core ships its own classes in the
# org.hsqldb.jdbc package (HSQLDBPool), and the hsqldb jar seals that package,
# so opening the test repository fails with "sealing violation". The shaded
# runtime jar merges both without the seal, so the tests run against it,
# launched with JUnit's console launcher.
#
# Usage:  ./run-qdn-tests.sh [test class ...]   (default: all Rngit*Tests classes)

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE="$(cd "$HERE/../.." && pwd)"
WORK="$HERE/.work-qdn"
JUNIT_VERSION=1.9.2
LAUNCHER="$HOME/.m2/repository/org/junit/platform/junit-platform-console-standalone/$JUNIT_VERSION/junit-platform-console-standalone-$JUNIT_VERSION.jar"

mkdir -p "$WORK"
[[ -f "$LAUNCHER" ]] || (cd "$CORE" && mvn -q dependency:get -Dartifact=org.junit.platform:junit-platform-console-standalone:$JUNIT_VERSION)

echo "Building the shaded jar and test classes..."
(cd "$CORE" && mvn -o -q package -DskipTests && mvn -o -q test-compile -DskipJUnitTests=true)
JAR="$(ls "$CORE"/target/qortal-*.jar | grep -v original | head -1)"

(cd "$CORE" && mvn -o -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$WORK/test-classpath.txt")
# Test-only jars (JUnit, Mockito, ...), without hsqldb: the shaded jar provides it, unsealed
TEST_DEPS="$(tr ':' '\n' < "$WORK/test-classpath.txt" | grep -E "junit|opentest4j|apiguardian|mockito|byte-buddy|objenesis" | tr '\n' ':')"

SELECT=()
if [[ $# -gt 0 ]]; then
    for c in "$@"; do SELECT+=(--select-class "org.qortal.rngit.$c"); done
else
    SELECT=(--select-package org.qortal.rngit --include-classname '.*Tests$')
fi

# Core's test settings and chain configs are resolved relative to the working directory
cd "$CORE"
java -jar "$LAUNCHER" --disable-banner --details=tree \
    -cp "$JAR:$CORE/target/test-classes:$TEST_DEPS" "${SELECT[@]}"
