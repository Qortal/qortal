#!/bin/sh

# There's no need to run as root, so don't allow it, for security reasons
if [ "$(id -u)" -eq 0 ]; then
	echo "Please su to non-root user before running"
	exit 1
fi

# Validate Java is installed and the minimum version is available
MIN_JAVA_VER='11'

if command -v java > /dev/null 2>&1; then
    # Example: openjdk version "11.0.6" 2020-01-14
    version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1,2)
    if echo "${version}" "${MIN_JAVA_VER}" | awk '{ if ($2 > 0 && $1 >= $2) exit 0; else exit 1}'; then
        echo 'Passed Java version check'
    else
        echo "Please upgrade your Java to version ${MIN_JAVA_VER} or greater"
        exit 1
    fi
else
  echo "Java is not available, please install Java ${MIN_JAVA_VER} or greater"
  exit 1
fi

# Refuse a second launch when a live process still owns the recorded PID.
if [ -f run.pid ]; then
    pid=$(cat run.pid) || exit 1
    case $pid in
        ''|*[!0-9]*|0|1) echo 'Invalid run.pid; inspect it before starting' >&2; exit 1 ;;
    esac
    if kill -0 "$pid" 2>/dev/null; then
        echo "Process $pid is still running; inspect it before starting another Core" >&2
        exit 1
    fi
fi

# Copy exactly one packaged JAR; never pick arbitrarily among multiple builds.
if [ ! -e qortal.jar ]; then
    set -- target/qortal*.jar
    if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
        echo 'Expected one target/qortal*.jar, or an installed qortal.jar' >&2
        exit 1
    fi
    cp -- "$1" qortal.jar || exit 1
fi
[ -r qortal.jar ] || { echo 'qortal.jar is not readable' >&2; exit 1; }

# Limits Java JVM stack size and maximum heap usage.
# Comment out for bigger systems, e.g. non-routers
# or when API documentation is enabled
# JAVA MEMORY SETTINGS BELOW - These settings are essentially optimized default settings.
# Combined with the latest changes on the Qortal Core in version 4.6.6 and beyond,
# should give a dramatic increase In performance due to optimized Garbage Collection.
# These memory arguments should work on machines with as little as 6GB of RAM.
# If you want to run on a machine with less than 6GB of RAM, it is suggested to increase the '50' below to '75'
# The Qortal Core will utilize only as much RAM as it needs, but up-to the amount set in percentage below.
JVM_MEMORY_ARGS="-XX:MaxRAMPercentage=50 -XX:+UseG1GC -Xss1024k"

# Although java.net.preferIPv4Stack is supposed to be false
# by default in Java 11, on some platforms (e.g. FreeBSD 12),
# it is overridden to be true by default. Hence we explicitly
# set it to false to obtain desired behaviour.
set -f # JVM options are split on whitespace without pathname expansion.
nohup nice -n 20 java \
	-Djava.net.preferIPv4Stack=false \
	${JVM_MEMORY_ARGS} \
	-jar qortal.jar \
	1>run.log 2>&1 &

# Save backgrounded process's PID
echo $! > run.pid
echo qortal running as pid $!
