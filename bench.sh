#!/usr/bin/env bash
# Runs the 5,000-polygon benchmark. Needs Java 17+ and Maven.
set -euo pipefail
mvn -B -q test-compile
mvn -B -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java -Xmx2g -cp "target/classes:target/test-classes:$(cat cp.txt)" com.moveinsync.zones.bench.ZoneBenchmark "$@"
