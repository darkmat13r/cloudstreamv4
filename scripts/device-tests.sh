#!/bin/zsh
# The device gate: boots a TV AVD headless and silent, runs Gradle against it alone, always stops it,
# and exits with Gradle's exit code.
#
#   JAVA_HOME=<Temurin 17> zsh scripts/device-tests.sh <avd> [gradle task or flag ...]
#
# Heap and worker limits are Gradle flags, so pass them in the arguments.
#
# With no Gradle task it runs every module's device tests. The test APKs are built before the emulator
# boots, so a compile error costs no boot and the emulator does not sit idle through a build. -read-only discards whatever the run
# changes in the AVD, -no-audio means nothing can reach the speakers, -cores 2 is the owner's limit on
# what it may use, and ANDROID_SERIAL keeps Gradle off any other emulator or device on this machine. In a git worktree add -x :app:generateGitHash.
: ${ANDROID_HOME:=$HOME/Library/Android/sdk}
export ANDROID_HOME
avd=${1:?usage: device-tests.sh <avd> [gradle task or flag ...]}
shift

flags=()
tasks=()
while (( $# )); do
    case $1 in
        -x|--exclude-task) flags+=($1 $2); shift ;;
        -*) flags+=($1) ;;
        *) tasks+=($1) ;;
    esac
    shift
done
(( ${#tasks} )) || tasks=(:feature:search:connectedAndroidDeviceTest :app:connectedPrereleaseDebugAndroidTest)

# What each connected task installs, which is everything it would compile once the emulator was up.
builds=()
for task in $tasks; do
    case $task in
        *connectedAndroidDeviceTest) builds+=(${task%connectedAndroidDeviceTest}packageAndroidDeviceTest) ;;
        *connected*AndroidTest)
            module=${task%connected*}
            variant=${task##*connected}
            builds+=(${module}assemble$variant ${module}assemble${variant%AndroidTest})
            ;;
    esac
done
if (( ${#builds} )); then
    TZ=UTC taskpolicy -c utility ./gradlew $flags $builds || exit $?
fi

PORT=5580
export ANDROID_SERIAL=emulator-$PORT
adb=$ANDROID_HOME/platform-tools/adb

"$ANDROID_HOME/emulator/emulator" -avd "$avd" -port $PORT \
    -no-window -no-audio -no-boot-anim -read-only -cores 2 -gpu swiftshader_indirect >/dev/null 2>&1 &
emulator=$!
trap '"$adb" -s $ANDROID_SERIAL emu kill >/dev/null 2>&1; wait $emulator 2>/dev/null' EXIT INT TERM

for _ in {1..120}; do
    [[ $("$adb" -s $ANDROID_SERIAL shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]] && break
    sleep 3
done
if [[ $("$adb" -s $ANDROID_SERIAL shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') != 1 ]]; then
    echo "device-tests: $avd did not boot" >&2
    exit 2
fi

# sys.boot_completed comes first: straight after it, Gradle's uninstall fails with
# DELETE_FAILED_INTERNAL_ERROR on two cores.
sleep 20

TZ=UTC taskpolicy -c utility ./gradlew $flags $tasks
