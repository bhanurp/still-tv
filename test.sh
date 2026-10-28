#!/bin/sh
set -eu
cd "$(dirname "$0")"
STILL_JAVA="${STILL_JAVA_HOME:-$PWD/.toolchain/jdk}"
mkdir -p build/tests
"$STILL_JAVA/bin/javac" -d build/tests app/src/dev/still/launcher/LauncherState.java tests/LauncherStateTest.java
"$STILL_JAVA/bin/java" -ea -cp build/tests LauncherStateTest
/usr/bin/python3 -m unittest discover -s tests -p 'test_*.py' -v
