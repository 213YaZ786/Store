#!/bin/bash
# usage: maestro/run-install.sh [adb serial]
# The install queue replayed on Calculator's real release (github.com/213YaZ786/Calculator, v0.3.6): an app.json made for the occasion
# (app/src/test/resources/catalog/app.json, as the CI will write it) is seeded through the debug receiver, which puts one entry in the queue as a
# tap on Install does; Maestro flows (https://docs.maestro.dev) then check what the screen shows and this script what Android recorded.
# Same shape as Files' maestro/run-install.sh and SMS's maestro/run-media.sh. Needs the debug build installed and com.yaz.calculator
# (the release build) NOT installed: it is installed here, then removed.
set -e
here="$(cd "$(dirname "$0")" && pwd)"
adb=(~/Android/Sdk/platform-tools/adb ${1:+-s "$1"})
pkg=com.yaz.store.debug
target=com.yaz.calculator
work=$(mktemp -d)
export MAESTRO_CLI_NO_ANALYTICS=true MAESTRO_DISABLE_UPDATE_CHECK=true MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
status=0

if "${adb[@]}" shell pm list packages | grep -q "^package:$target\$"; then
  echo "$target is installed: remove it first, this run installs it"
  exit 1
fi
# The app's own permission to install, as the setup guide has the user grant it, and its notifications.
"${adb[@]}" shell appops set $pkg REQUEST_INSTALL_PACKAGES allow
"${adb[@]}" shell pm grant $pkg android.permission.POST_NOTIFICATIONS

# The app.json of the real release, and the same with a version that is not the file's.
src="$here/../app/src/test/resources/catalog/app.json"
sed 's/"versionCode":14/"versionCode":15/' "$src" > "$work/other-version.json"
"${adb[@]}" shell "run-as $pkg mkdir -p files"
seed() { "${adb[@]}" shell "run-as $pkg sh -c 'cat > files/app.json'" < "$1"; }
send() { # tag
  "${adb[@]}" shell am start -n $pkg/com.yaz.store.MainActivity >/dev/null
  sleep 2
  "${adb[@]}" shell am broadcast -n $pkg/com.yaz.store.DebugInstallReceiver --es repo Calculator --es tag "$1" --es json app.json >/dev/null
}
installed() { "${adb[@]}" shell pm list packages | grep -q "^package:$target\$"; }

# A version that is not the file's, and a tag that has no release: refused, nothing installed.
seed "$work/other-version.json"; send v0.3.6
maestro ${1:+--device "$1"} test -e MSG="The file is another version than the release says." "$here/install-failed.yaml" || status=1
seed "$src"; send v0.0.0
maestro ${1:+--device "$1"} test -e MSG="The release has no such file." "$here/install-failed.yaml" || status=1
installed && { echo "installed after a refusal"; status=1; }

# The first install: Android asks once. Store then holds the app.
send v0.3.6
maestro ${1:+--device "$1"} test "$here/install-first.yaml" || status=1
installed || { echo "not installed"; status=1; }
dump=$("${adb[@]}" shell dumpsys package $target)
echo "$dump" | grep -iE "installerPackageName|updateOwner|installInitiatingPackageName"
echo "$dump" | grep -q "installerPackageName=$pkg" || { echo "Store is not the installer of record"; status=1; }

# The same release again: silent, Store being the installer of record.
send v0.3.6
maestro ${1:+--device "$1"} test "$here/install-update.yaml" || status=1

# Nothing left behind: no download in the cache.
left=$("${adb[@]}" shell run-as $pkg ls cache/downloads | wc -l)
[ "$left" -eq 0 ] || { echo "downloads left: $left"; status=1; }
"${adb[@]}" shell pm uninstall $target >/dev/null
rm -rf "$work"
exit $status
