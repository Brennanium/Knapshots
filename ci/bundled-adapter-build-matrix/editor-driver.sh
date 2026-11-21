#!/bin/sh
set -eu
[ "$#" -eq 2 ] && [ "$1" = "--ide-path" ] || { echo "Expected --ide-path <IDE>" >&2; exit 2; }
: "${KNAPSHOTS_CHECKOUT:?Set KNAPSHOTS_CHECKOUT}"
version=${KNAPSHOTS_VERSION:-0.1.0-ci}
gradle="$KNAPSHOTS_CHECKOUT/gradlew"
# Compile ordinary runtime jars before packaging the adapter. The isolated builder avoids
# sharing Gradle plugin classloaders with the main Dokka build.
"$gradle" -p "$KNAPSHOTS_CHECKOUT" "-PVERSION_NAME=$version" \
  :knapshots-snapshots:jar :knapshots-editor:jar :knapshots-ide-runner:jar --no-configuration-cache
"$gradle" -p "$KNAPSHOTS_CHECKOUT/ci/bundled-adapter-builder" buildPlugin \
  "-PknapshotsVersion=$version" "-Pknapshots.idePath=$2" --no-configuration-cache
adapter="$KNAPSHOTS_CHECKOUT/ci/bundled-adapter-builder/build/distributions/knapshots-adapter-builder-$version.zip"
[ -f "$adapter" ] || { echo "Missing built adapter: $adapter" >&2; exit 1; }
"$gradle" -p "$KNAPSHOTS_CHECKOUT" publishAllPublicationsToConsumerVerificationRepository \
  "-PVERSION_NAME=$version" "-Pknapshots.releaseAdapterArchive=$adapter" --no-configuration-cache
