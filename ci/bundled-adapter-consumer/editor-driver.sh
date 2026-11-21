#!/bin/sh
# Invoked once per IDE by editorCompatibility. The selected Knapshots release must already
# exist in KNAPSHOTS_REPOSITORY (or Maven Central); no adapter is built or passed here.
set -eu
: "${KNAPSHOTS_CHECKOUT:?Set KNAPSHOTS_CHECKOUT}"
: "${KPATHS_CHECKOUT:?Set KPATHS_CHECKOUT}"
: "${KNAPSHOTS_KEFS_ARCHIVE:?The matrix must supply the KEFS archive}"
ide=''; kotlin=''; kefs=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    --ide-path) ide=$2; shift 2 ;;
    --ide-kotlin-version) kotlin=$2; shift 2 ;;
    --kefs-version) kefs=$2; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
: "${ide:?Missing IDE path}" "${kotlin:?Missing IDE Kotlin version}" "${kefs:?Missing KEFS version}"
python3 "$KPATHS_CHECKOUT/scripts/verify-kefs-compatibility.py" \
  --ide-path "$ide" --ide-kotlin-version "$kotlin" --kefs-version "$kefs" --allow-untested-combination
export KNAPSHOTS_IDE_PATH="$ide" KNAPSHOTS_KEFS_VERSION="$kefs"
export KNAPSHOTS_WRAPPER_ROOT="$KNAPSHOTS_CHECKOUT"
export KPATHS_REPOSITORY="$KPATHS_CHECKOUT/build/consumer-verification/repository"
export KPATHS_KEFS_REPOSITORY="$KPATHS_CHECKOUT/kpaths-kefs-ide-fixture/build/kefs-repository"
exec "$KNAPSHOTS_CHECKOUT/gradlew" -p "$KNAPSHOTS_CHECKOUT/ci/bundled-adapter-consumer" test \
  "-PknapshotsVersion=${KNAPSHOTS_VERSION:-0.1.0}" --no-configuration-cache
