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
#
# Screenshots: what assertScreenshot wrote comes off the device before the emulator stops, to <module>/build/screenshots and
# /private/tmp/cloudstream-shared/review/shots/<module>. With -Pandroid.testInstrumentationRunnerArguments.screenshots=record,
# the recorded PNGs also replace the module's references, for a person to look at in `git diff` and commit.
: ${ANDROID_HOME:=$HOME/Library/Android/sdk}
export ANDROID_HOME
avd=${1:?usage: device-tests.sh <avd> [gradle task or flag ...]}
# The shared slot wrapper may hand this run the second TV emulator (owner, 2026-11-05). Both have the same image and size.
avd=${CS_EMULATOR_AVD:-$avd}
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
(( ${#tasks} )) || tasks=(:feature:search:connectedAndroidDeviceTest :core:designsystem:connectedAndroidDeviceTest :app:connectedPrereleaseDebugAndroidTest)

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

PORT=${CS_EMULATOR_PORT:-5580}
export ANDROID_SERIAL=emulator-$PORT
adb=$ANDROID_HOME/platform-tools/adb

"$ANDROID_HOME/emulator/emulator" -avd "$avd" -port $PORT \
    -no-window -no-audio -no-boot-anim -read-only -cores 2 -gpu swiftshader_indirect >/dev/null 2>&1 &
emulator=$!
# Never waits for an emulator that adb cannot reach: that held the shared Gradle slot for 7 hours on 2026-11-04.
stop_emulator() {
    "$adb" -s $ANDROID_SERIAL emu kill >/dev/null 2>&1
    for _ in {1..15}; do kill -0 $emulator 2>/dev/null || return 0; sleep 2; done
    kill $emulator 2>/dev/null
    sleep 5
    kill -9 $emulator 2>/dev/null
}
trap stop_emulator EXIT INT TERM

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
gradle_exit=$?

review=/private/tmp/cloudstream-shared/review/shots
for task in $tasks; do
    [[ $task == *connectedAndroidDeviceTest ]] || continue
    module=${${task%:connectedAndroidDeviceTest}#:}
    module=${module//://}
    output=$module/build/screenshots
    rm -rf $output
    mkdir -p $output
    "$adb" -s $ANDROID_SERIAL pull /data/local/tmp/cloudstream-shots/$module/. $output >/dev/null 2>&1
    if [[ ${flags[(r)*screenshots=record]} != "" ]] && [[ -d $output/recorded ]]; then
        mkdir -p $module/src/androidDeviceTest/resources/screenshots
        cp $output/recorded/*.png(N) $module/src/androidDeviceTest/resources/screenshots/
    fi
    if [[ -d ${review:h} ]]; then
        # One folder per branch: several branches record the same module at once, and a shared folder mixed them.
        branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null); branch=${${branch:-detached}//\//-}
        shots=$review/$branch/${module//\//-}
        mkdir -p $shots
        rm -rf $shots/failures
        cp -R $output/. $shots/
    fi
done
exit $gradle_exit
