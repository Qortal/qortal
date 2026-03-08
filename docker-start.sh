#!/bin/sh
set -eu

DEFAULT_JVM_MEMORY_ARGS='-XX:MaxRAMPercentage=25 -XX:+UseG1GC -Xss1024k'
START_ARGS_FILE="${QORTAL_START_ARGUMENTS_FILE:-/qortal/start-arguments.txt}"
SETTINGS_FILE="${QORTAL_SETTINGS_FILE:-/qortal/settings.json}"

if [ ! -f "${SETTINGS_FILE}" ]; then
    printf '{}\n' > "${SETTINGS_FILE}"
fi

if [ ! -f "${START_ARGS_FILE}" ]; then
    printf '%s\n' "${QORTAL_JVM_MEMORY_ARGS:-${DEFAULT_JVM_MEMORY_ARGS}}" > "${START_ARGS_FILE}"
fi

# Convert start-arguments file to a single shell-split argument string.
file_args="$(awk '
{
    sub(/#.*/, "");
    if (NF) {
        for (i = 1; i <= NF; i++) {
            printf "%s ", $i;
        }
    }
}
' "${START_ARGS_FILE}" 2>/dev/null || true)"

jvm_memory_args="${file_args:-${QORTAL_JVM_MEMORY_ARGS:-${DEFAULT_JVM_MEMORY_ARGS}}}"
echo "Using JVM memory args from ${START_ARGS_FILE}: ${jvm_memory_args}"
echo "Using settings file: ${SETTINGS_FILE}"
if [ -f "${SETTINGS_FILE}" ]; then
    api_port="$(grep -E '"apiPort"[[:space:]]*:' "${SETTINGS_FILE}" | head -n1 | sed -E 's/.*:[[:space:]]*([0-9]+).*/\1/' || true)"
    p2p_port="$(grep -E '"listenPort"[[:space:]]*:' "${SETTINGS_FILE}" | head -n1 | sed -E 's/.*:[[:space:]]*([0-9]+).*/\1/' || true)"
    if [ -n "${api_port}" ] || [ -n "${p2p_port}" ]; then
        echo "Configured ports from settings.json: apiPort=${api_port:-?} listenPort=${p2p_port:-?}"
    fi
fi

if [ "$#" -eq 0 ]; then
    set -- -jar /usr/local/qortal/qortal.jar "${SETTINGS_FILE}"
fi

# shellcheck disable=SC2086
exec java ${jvm_memory_args} -Djava.net.preferIPv4Stack=false "$@"
