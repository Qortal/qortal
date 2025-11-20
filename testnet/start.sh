#!/bin/sh

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
if [ ! -e qortal.jar -a -f ../target/qortal*.jar ]; then
	echo "Copying Maven-built Qortal JAR to correct pathname"
	cp ../target/qortal*.jar qortal.jar
fi

# Limits Java JVM stack size and maximum heap usage.
# Comment out for bigger systems, e.g. non-routers
# or when API documentation is enabled
JVM_MEMORY_ARGS="-Xss256m -XX:+UseSerialGC"

# Although java.net.preferIPv4Stack is supposed to be false
# by default in Java 11, on some platforms (e.g. FreeBSD 12),
# it is overridden to be true by default. Hence we explicitly
# set it to false to obtain desired behaviour.
nohup nice -n 20 java \
	-Djava.net.preferIPv4Stack=false \
	${JVM_MEMORY_ARGS} \
	-jar qortal.jar \
	settings-test.json \
	1>run.log 2>&1 &

# Save backgrounded process's PID
echo $! > run.pid
echo qortal running as pid $!
