#!/bin/sh
# NOTE: Alpine linux is busybox-based - there is no bash

# DESCRIPTION
#
# This is a generic jar-as-a-service launcher designed to run a single-jar JVM application with Docker.
#
# FEATURES
#
# This script:
# 1. Sets MaxRAMPercentage to 90% to make the most use of available/configured
#    RAM.
# 2. Enables Transparent Huge Pages (THP) to make memory access more efficient.
# 3. Prints all JVM options on startup.
# 4. Enables logging of garbage collection through Java Unified Logging.
# 5. Enables String Deduplication through the garbage collector.
# 6. Uses the G1 collector, tune to attempt to achieve low pause times.
# 7. Runs whatever jar file is located in the current working directory, passing
#    in any command-line arguments.

OPTIONS="-server"
OPTIONS="$OPTIONS -XX:MaxRAMPercentage=80"
OPTIONS="$OPTIONS -XX:-UseCompressedOops"
OPTIONS="$OPTIONS -XX:+UseLargePages"
OPTIONS="$OPTIONS -XX:+UseTransparentHugePages"
#OPTIONS="$OPTIONS -XX:+PrintFlagsFinal"
OPTIONS="$OPTIONS -Xlog:gc"
OPTIONS="$OPTIONS -XX:+UseStringDeduplication"
OPTIONS="$OPTIONS -XX:MaxGCPauseMillis=100"
OPTIONS="$OPTIONS -XshowSettings:vm"

JARFILE=`shift`

echo "Running 'java ${OPTIONS} -jar $JARFILE \"$@\"'"
java ${OPTIONS} -jar $JARFILE "$@"