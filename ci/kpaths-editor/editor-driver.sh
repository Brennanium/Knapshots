#!/bin/sh
set -eu
: "${KPATHS_CHECKOUT:?Set KPATHS_CHECKOUT to the KPaths checkout}"
: "${KNAPSHOTS_CHECKOUT:?Set KNAPSHOTS_CHECKOUT to the Knapshots checkout}"
ide=''
kefs=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    --ide-path) ide=$2; shift 2 ;;
    --ide-kotlin-version) kotlin=$2; shift 2 ;;
    --kefs-version) kefs=$2; shift 2 ;;
    *) echo "Unknown driver argument: $1" >&2; exit 2 ;;
  esac
done
: "${ide:?Missing IDE path}" "${kotlin:?Missing IDE Kotlin version}" "${kefs:?Missing KEFS version}"
# Use KPaths' existing verified artifact preparation; Knapshots owns the imported-project
# assertions below. The old --ide-probe consumer loop is intentionally not invoked.
python3 "$KPATHS_CHECKOUT/scripts/verify-kefs-compatibility.py" \
  --ide-path "$ide" --ide-kotlin-version "$kotlin" --kefs-version "$kefs" --allow-untested-combination
for mode in enabled disabled diagnostic; do
  "$KNAPSHOTS_CHECKOUT/gradlew" -p "$KNAPSHOTS_CHECKOUT" \
    -Pkotlin.daemon.jvmargs=-Xmx2g -Pknapshots.enableIntellijAdapter \
    "-Pknapshots.idePath=$ide" "-Pknapshots.kefsVersion=$kefs" \
    "-Pknapshots.kpathsCheckout=$KPATHS_CHECKOUT" "-Pknapshots.kpathsMode=$mode" \
    -Pknapshots.includeIdeIntegration :knapshots-ide-runner:test \
    --tests '*KPathsEditorIntegrationTest' --no-configuration-cache
  report="$KNAPSHOTS_CHECKOUT/knapshots-ide-runner/build/test-results/test/TEST-dev.knapshots.testing.ide.KPathsEditorIntegrationTest.xml"
  python3 -c 'import sys, xml.etree.ElementTree as ET; r=ET.parse(sys.argv[1]).getroot(); ok=int(r.get("tests", 0)) > 0 and all(int(r.get(k, 0)) == 0 for k in ("skipped", "failures", "errors")); sys.exit(0 if ok else "KPaths editor test did not pass without skips")' "$report"
  destination="$KNAPSHOTS_CHECKOUT/build/kpaths-editor-results/$mode"
  mkdir -p "$destination"
  cp "$report" "$destination/"
done
