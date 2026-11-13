#!/usr/bin/env bash

# Run from the Core's working directory. Never guess a PID from another node.
api_port=62391
timeout=${QORTAL_STOP_TIMEOUT:-120}
usage() { echo "Usage: $0 [-t|--testnet] [--port PORT] [--timeout SECONDS]"; }
die() { printf '%s\n' "$*" >&2; exit 1; }
while (( $# )); do
  case $1 in
    -t|--testnet) api_port=62391; shift ;;
    --port|--timeout)
      (( $# >= 2 )) || { usage >&2; exit 2; }
      if [[ $1 == --port ]]; then api_port=$2; else timeout=$2; fi
      shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done
[[ $api_port =~ ^[1-9][0-9]{0,4}$ ]] && (( 10#$api_port <= 65535 )) || die 'Invalid API port'
[[ $timeout =~ ^[1-9][0-9]{0,5}$ ]] || die 'Timeout must be a positive number of seconds'
timeout=$((10#$timeout))

pid=
if [[ -e run.pid ]]; then
  pid=$(cat run.pid) || die 'Cannot read run.pid'
  [[ $pid =~ ^[1-9][0-9]{0,9}$ ]] && (( pid > 1 && pid <= 2147483647 )) || die 'Invalid run.pid; refusing to signal a process'
fi
process_state() { ps -p "$pid" -o stat= 2>/dev/null; }
running() {
  local state
  state=$(process_state) || return 1
  state=${state//[[:space:]]/}
  [[ -n $state && $state != Z* ]]
}
same_core() {
  local args process_cwd
  args=$(ps -p "$pid" -o args= 2>/dev/null) || return 1
  [[ $args =~ (^|/)java[[:space:]] && $args =~ [[:space:]]-jar[[:space:]]+([^[:space:]]*/)?qortal\.jar([[:space:]]|$) ]] || return 1
  # On Linux, a stale PID file must not stop another Core installation.
  if [[ -d /proc/$pid ]]; then
    process_cwd=$(readlink "/proc/$pid/cwd") || return 1
    [[ $process_cwd == "$(pwd -P)" ]] || return 1
  fi
}
if [[ -n $pid ]] && running; then
  same_core || die "PID $pid does not identify Qortal in this directory; refusing to stop it"
elif [[ -n $pid ]]; then
  pid=
fi

apikey=
if [[ -f apikey.txt ]]; then
  apikey=$(cat apikey.txt) || die 'Cannot read apikey.txt'
  apikey=${apikey%$'\r'}
  [[ $apikey != *$'\n'* && $apikey != *$'\r'* ]] || die 'API key must contain a single line'
fi
success=false
if [[ -n $apikey ]]; then
  echo 'Stopping Qortal via API...'
  if curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
      --header "X-API-KEY: $apikey" \
      --url "http://127.0.0.1:$api_port/admin/stop" >/dev/null; then
    success=true
  fi
fi
if ! "$success" && [[ -n $pid ]]; then
  if running; then
    same_core || die 'Process identity changed; refusing to signal it'
    echo "Stopping Qortal process $pid..."
    kill -TERM "$pid" || die "Could not stop process $pid"
  fi
  success=true
fi
"$success" || die 'Stop failed: no successful API request or verified Core PID'
if [[ -z $pid ]]; then
  echo 'Shutdown requested via API; no verified PID is available to monitor completion'
  exit 0
fi
echo "Waiting up to $timeout seconds for Qortal process $pid..."
deadline=$((SECONDS + timeout))
while running; do
  same_core || die 'Process identity changed while waiting; run.pid retained'
  (( SECONDS < deadline )) || die "Qortal is still running after $timeout seconds; inspect the logs/thread dumps. run.pid retained."
  sleep 1
done
if [[ $(cat run.pid 2>/dev/null) == "$pid" ]]; then rm -f run.pid; fi
echo 'Qortal stopped'
