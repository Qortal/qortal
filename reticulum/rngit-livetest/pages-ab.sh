#!/usr/bin/env bash
#
# A/B test of Core's Nomad Network page node against the reference pages.py:
# both answer the same page and file requests over copies of one fixture group,
# and every response must match byte for byte, except the footer's version
# and generation time, and the known differences listed in KNOWN below.
#
# Usage:  ./pages-ab.sh        (RNS_SRC defaults to ~/git/Reticulum)
#         KEEP=1 ./pages-ab.sh keeps the work directory for inspection

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE="$(cd "$HERE/../.." && pwd)"
RNS_SRC="${RNS_SRC:-$HOME/git/Reticulum}"
WORK="$HERE/.work-pages"
export PYTHONPATH="$RNS_SRC"
export TZ=UTC
export RNS_CONFIG="$WORK/rns"

rm -rf "$WORK"
mkdir -p "$WORK/rns"
[[ -d "$RNS_SRC/RNS" ]] || { echo "FAIL: no Reticulum checkout at $RNS_SRC"; exit 1; }

(cd "$CORE" && mvn -o -q test-compile -DskipJUnitTests=true) || { echo "FAIL: test-compile"; exit 1; }
(cd "$CORE" && mvn -o -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$WORK/classpath.txt") \
    || { echo "FAIL: classpath"; exit 1; }
CP="$(cat "$WORK/classpath.txt"):$CORE/target/classes:$CORE/target/test-classes"

python3 "$HERE/pages_ab.py" fixture "$WORK/fixture" || { echo "FAIL: fixture"; exit 1; }
cp -a "$WORK/fixture" "$WORK/fixture-java"
cp -a "$WORK/fixture" "$WORK/fixture-python"
python3 "$HERE/pages_ab.py" requests "$WORK/fixture" "$WORK/requests.json" || { echo "FAIL: requests"; exit 1; }

java -cp "$CP" org.qortal.rngit.RngitPagesDump "$WORK/fixture-java" "$WORK/requests.json" "$WORK/java" \
    || { echo "FAIL: java dump"; exit 1; }
python3 "$HERE/pages_ab.py" dump "$WORK/fixture-python" "$WORK/requests.json" "$WORK/python" 2> "$WORK/python.log" \
    || { echo "FAIL: python dump"; exit 1; }

# Responses that differ on purpose (see RngitPages): the request and why
declare -A KNOWN=(
    ['/page/repo.mu {"var_g": "demo", "var_r": "fork"}']="never synced: the reference shows an age since 1970, Core none"
)

normalise() {
    sed -E 's/Served by rngit [^`]*`/Served by rngit V`/; s/Generated in [^`]*`f/Generated in T`f/' "$1"
}

TOTAL=$(python3 -c "import json; print(len(json.load(open('$WORK/requests.json'))))")
FAILURES=0
for ((i = 0; i < TOTAL; i++)); do
    n=$(printf '%03d' "$i")
    request=$(python3 -c "import json; r = json.load(open('$WORK/requests.json'))[$i]; print(r['path'], json.dumps(r['data']))")
    if cmp -s <(normalise "$WORK/java/$n.out") <(normalise "$WORK/python/$n.out"); then
        echo "  PASS $n $request"
    elif grep -q '^EXCEPTION' "$WORK/python/$n.out" && [[ "$(cat "$WORK/java/$n.out")" == NONE ]]; then
        echo "  PASS $n $request (the reference raises: no response either way)"
    elif [[ -n "${KNOWN[$request]:-}" ]]; then
        echo "  KNOWN $n $request: ${KNOWN[$request]}"
    else
        echo "  FAIL $n $request"
        diff <(normalise "$WORK/python/$n.out") <(normalise "$WORK/java/$n.out") | head -20 | sed 's/^/        /'
        FAILURES=$((FAILURES + 1))
    fi
done

echo
if [[ $FAILURES -eq 0 ]]; then echo "All $TOTAL responses match"; else echo "$FAILURES of $TOTAL responses differ"; fi
[[ -n "${KEEP:-}" ]] || [[ $FAILURES -ne 0 ]] || rm -rf "$WORK"
exit $FAILURES
