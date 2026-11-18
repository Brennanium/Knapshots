#!/bin/sh
set -eu
: "${KPATHS_CHECKOUT:?Set KPATHS_CHECKOUT to the KPaths checkout}"
exec python3 "$KPATHS_CHECKOUT/scripts/verify-kefs-compatibility.py" "$@" --allow-untested-combination --ide-probe
