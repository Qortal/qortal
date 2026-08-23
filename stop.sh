#!/usr/bin/env bash

# Check for color support
if [ -t 1 ]; then
	ncolors=$( tput colors )
	if [ -n "${ncolors}" -a "${ncolors}" -ge 8 ]; then
		if normal="$( tput sgr0 )"; then
			# use terminfo names
			red="$( tput setaf 1 )"
			green="$( tput setaf 2)"
		else
			# use termcap names for FreeBSD compat
			normal="$( tput me )"
			red="$( tput AF 1 )"
			green="$( tput AF 2)"
		fi
	fi
fi

# Track the pid if we can find it
read pid 2>/dev/null <run.pid
is_pid_valid=$?

# Swap out the API port if the --testnet (or -t) argument is specified
api_port=12391
if [[ "$@" = *"--testnet"* ]] || [[  "$@" = *"-t"* ]]; then
  api_port=62391
fi

# Attempt to locate the process ID if we don't have one
if [ -z "${pid}" ]; then
  pid=$(ps aux | grep '[q]ortal.jar' | head -n 1 | awk '{print $2}')
  is_pid_valid=$?
fi

# Locate the API key if it exists
apikey=$(cat apikey.txt)
success=0

# Try and stop via the API
if [ -n "$apikey" ]; then
  echo "Stopping Qortal via API..."
  if curl --url "http://localhost:${api_port}/admin/stop?apiKey=$apikey" 1>/dev/null 2>&1; then
    success=1
  fi
fi

# Try to kill process with SIGTERM
if [ "$success" -ne 1 ] && [ -n "$pid" ]; then
  echo "Stopping Qortal process $pid..."
  if kill -15 "${pid}"; then
    success=1
  fi
fi

# Warn and exit if still no success
if [ "$success" -ne 1 ]; then
  if [ -n "$pid" ]; then
    echo "${red}Stop command failed - not running with process id ${pid}?${normal}"
  else
    echo "${red}Stop command failed - not running?${normal}"
  fi
  exit 1
fi

if [ "$success" -eq 1 ]; then
  echo "Qortal node should be shutting down"
  if [ "${is_pid_valid}" -eq 0 ]; then
    # Wait up to GRACE_SECONDS for a clean exit. If leaked non-daemon threads keep the
    # JVM alive past that (as in the Reticulum test-14 watchdog/netty leaks), the process
    # would otherwise linger forever holding ports 12391/12392/12394 — so the next start
    # can't bind and only a reboot recovers. Force-kill after the grace period so ports
    # are always released.
    GRACE_SECONDS=120
    waited=0
    echo -n "Monitoring for Qortal node to end"
    while s=`ps -p $pid -o stat=` && [[ "$s" && "$s" != 'Z' ]]; do
      if [ "${waited}" -ge "${GRACE_SECONDS}" ]; then
        echo
        echo "${red}Qortal did not stop within ${GRACE_SECONDS}s - forcing shutdown (kill -9 ${pid})${normal}"
        kill -9 "${pid}" 2>/dev/null
        sleep 2
        break
      fi
      echo -n .
      sleep 1
      waited=$((waited + 1))
    done
    echo
    if ps -p $pid > /dev/null 2>&1; then
      echo "${red}Warning: process ${pid} may still be running${normal}"
    else
      echo "${green}Qortal ended${normal}"
    fi
    rm -f run.pid
  fi
fi

exit 0
