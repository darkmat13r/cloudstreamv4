#!/bin/zsh
# The device gate: boots a TV AVD headless and silent, runs Gradle against it alone, always stops it,
# and exits with Gradle's exit code.
#
#   JAVA_HOME=<Temurin 17> zsh scripts/device-tests.sh <avd> [gradle task or flag ...]
#
# With no Gradle arguments it runs every module's device tests. -read-only discards whatever the run
# changes in the AVD, -no-audio means nothing can reach the speakers, and ANDROID_SERIAL keeps Gradle
# off any other device plugged into this machine. In a git worktree add -x :app:generateGitHash.
: ${ANDROID_HOME:=$HOME/Library/Android/sdk}
export ANDROID_HOME
avd=${1:?usage: device-tests.sh <avd> [gradle task or flag ...]}
shift
PORT=5580
export ANDROID_SERIAL=emulator-$PORT
adb=$ANDROID_HOME/platform-tools/adb

"$ANDROID_HOME/emulator/emulator" -avd "$avd" -port $PORT \
    -no-window -no-audio -no-boot-anim -no-snapshot -read-only >/dev/null 2>&1 &
emulator=$!
trap '"$adb" emu kill >/dev/null 2>&1; wait $emulator 2>/dev/null' EXIT INT TERM

for _ in {1..120}; do
    [[ $("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]] && break
    sleep 3
done
if [[ $("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') != 1 ]]; then
    echo "device-tests: $avd did not boot" >&2
    exit 2
fi

if (( $# == 0 )); then set -- :feature:search:connectedAndroidDeviceTest; fi
TZ=UTC ./gradlew -Dorg.gradle.jvmargs="-Xmx14G -Dfile.encoding=UTF-8" "$@"
