#!/bin/sh
set -eu
if [ "$#" -ne 2 ] || [ "$1" != "--ide-path" ]; then
  echo "Expected --ide-path <Android Studio installation>" >&2
  exit 2
fi
: "${KNAPSHOTS_CHECKOUT:?Set KNAPSHOTS_CHECKOUT to the Knapshots checkout}"
exec "$KNAPSHOTS_CHECKOUT/gradlew" -p "$KNAPSHOTS_CHECKOUT" \
  -Pkotlin.daemon.jvmargs=-Xmx2g \
  -Pknapshots.enableIntellijAdapter \
  "-Pknapshots.idePath=$2" \
  -Pknapshots.includeIdeIntegration \
  :knapshots-ide-runner:test --tests '*IntellijAdapterIntegrationTest' --no-configuration-cache
