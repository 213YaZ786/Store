#!/bin/bash
# usage: maestro/run-home.sh [adb serial]
# Home, the app page, Settings and the setup guide on the phone's own data: Store's data is cleared (the guide shows), skipped, then a seed
# (data/catalog/Seeded, debug builds only) puts Calculator offered at 0.3.7 and Dialer beside the live list. Needs the debug build installed and
# Calculator's release 0.3.6 installed (maestro/run-install.sh installs it). Same shape as run-install.sh and SMS's maestro/run-media.sh
# (https://docs.maestro.dev).
set -e
here="$(cd "$(dirname "$0")" && pwd)"
adb=(~/Android/Sdk/platform-tools/adb ${1:+-s "$1"})
pkg=com.yaz.store.debug
export MAESTRO_CLI_NO_ANALYTICS=true MAESTRO_DISABLE_UPDATE_CHECK=true MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
status=0
"${adb[@]}" shell pm list packages | grep -q "^package:com.yaz.calculator\$" || { echo "install Calculator 0.3.6 first (run-install.sh)"; exit 1; }

"${adb[@]}" shell pm clear $pkg >/dev/null
maestro ${1:+--device "$1"} test "$here/setup.yaml" || status=1

calc='{"package":"com.yaz.calculator","versionCode":15,"versionName":"0.3.7","label":"Calculator","minSdk":31,"description":{"en-US":"Calculator, converter and sheets"},"apks":["Calculator-0.3.7.apk"]}'
dial='{"package":"com.yaz.dialer","versionCode":99,"versionName":"0.14.20","label":"Dialer","minSdk":31,"description":{"en-US":"Calls, history and speed dial"},"apks":["Dialer-0.14.20.apk"]}'
seed=$(python3 -I -c 'import json,sys; print(json.dumps([{"repo":"Calculator","tag":"v0.3.6","json":sys.argv[1]},{"repo":"Dialer","tag":"v0.14.20","json":sys.argv[2]}]))' "$calc" "$dial")
"${adb[@]}" shell "run-as $pkg sh -c 'cat > files/debug-seed.json'" <<< "$seed"
maestro ${1:+--device "$1"} test "$here/home.yaml" || status=1

# Left as found: the seed is removed.
"${adb[@]}" shell "run-as $pkg rm -f files/debug-seed.json"
exit $status
