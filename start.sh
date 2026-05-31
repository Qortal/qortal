#!/bin/sh

# There's no need to run as root, so don't allow it, for security reasons
if [ "$USER" = "root" ]; then
	echo "Please su to non-root user before running"
	exit
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

# No qortal.jar but we have a Maven built one?
# Be helpful and copy across to correct location
if [ ! -e qortal.jar -a -f target/qortal*.jar ]; then
	echo "Copying Maven-built Qortal JAR to correct pathname"
	cp target/qortal*.jar qortal.jar
fi

# JVM memory settings.
# MaxRAMPercentage caps the heap at a fraction of total physical RAM.
# 40% leaves enough room for JVM non-heap (Metaspace, CodeHeap, thread stacks)
# and native memory (HSQLDB, gRPC, network buffers) without exhausting swap.
# On a 8 GB machine this gives ~3.2 GB heap; on a 16 GB machine ~6.4 GB.
# Reduce to 30 on machines with less than 6 GB of RAM.
# MaxMetaspaceSize caps class-metadata growth; 256m is generous given observed ~80 MB usage.
JVM_MEMORY_ARGS="-XX:MaxRAMPercentage=40 -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -Xss1024k"

# Although java.net.preferIPv4Stack is supposed to be false
# by default in Java 11, on some platforms (e.g. FreeBSD 12),
# it is overridden to be true by default. Hence we explicitly
# set it to false to obtain desired behaviour.
nohup nice -n 20 java \
	-Djava.net.preferIPv4Stack=false \
	${JVM_MEMORY_ARGS} \
	-jar qortal.jar \
	1>run.log 2>&1 &

# Save backgrounded process's PID
echo $! > run.pid
echo qortal running as pid $!
