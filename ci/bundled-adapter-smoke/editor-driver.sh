#!/bin/sh
set -eu
: "${KNAPSHOTS_CHECKOUT:?Set KNAPSHOTS_CHECKOUT}"
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
export KNAPSHOTS_IDE_PATH="$ide" KNAPSHOTS_KEFS_VERSION="$kefs" KNAPSHOTS_WRAPPER_ROOT="$KNAPSHOTS_CHECKOUT"
export KNAPSHOTS_FIXTURE_REPOSITORY="$KNAPSHOTS_CHECKOUT/ci/bundled-adapter-smoke/build/fixture-repository"
version=${KNAPSHOTS_VERSION:-0.1.0}
gradle="$KNAPSHOTS_CHECKOUT/gradlew"
"$gradle" -p "$KNAPSHOTS_CHECKOUT" :knapshots-test-compiler-plugin:jar "-PVERSION_NAME=$version" --no-configuration-cache
"$gradle" -p "$KNAPSHOTS_CHECKOUT/knapshots-intellij-fixture" ideFixturePluginJar "-Pknapshots.idePath=$ide" --no-configuration-cache
"$gradle" -p "$KNAPSHOTS_CHECKOUT/ci/fixture-gradle-plugin" publishAllPublicationsToFixtureRepository --no-configuration-cache
compiler="$KNAPSHOTS_FIXTURE_REPOSITORY/dev/knapshots/testing/fixture-compiler"
mkdir -p "$compiler/1.0.0" "$compiler/$kotlin-1.0.0"
cp "$KNAPSHOTS_CHECKOUT/knapshots-test-compiler-plugin/build/libs/knapshots-test-compiler-plugin-$version.jar" "$compiler/1.0.0/fixture-compiler-1.0.0.jar"
cp "$KNAPSHOTS_CHECKOUT/knapshots-intellij-fixture/build/libs/knapshots-fixture-ide-compiler-plugin.jar" "$compiler/$kotlin-1.0.0/fixture-compiler-$kotlin-1.0.0.jar"
cat > "$compiler/1.0.0/fixture-compiler-1.0.0.pom" <<'POM'
<project><modelVersion>4.0.0</modelVersion><groupId>dev.knapshots.testing</groupId><artifactId>fixture-compiler</artifactId><version>1.0.0</version></project>
POM
printf '<metadata><groupId>dev.knapshots.testing</groupId><artifactId>fixture-compiler</artifactId><versioning><versions><version>%s-1.0.0</version></versions></versioning></metadata>\n' "$kotlin" > "$compiler/maven-metadata.xml"
# KEFS may look for a checksum next to a local repository artifact.
for path in "$compiler/maven-metadata.xml" \
  "$compiler/1.0.0/fixture-compiler-1.0.0.jar" \
  "$compiler/1.0.0/fixture-compiler-1.0.0.pom" \
  "$compiler/$kotlin-1.0.0/fixture-compiler-$kotlin-1.0.0.jar"; do
  if command -v md5sum >/dev/null 2>&1; then
    md5sum "$path" | cut -d ' ' -f 1 > "$path.md5"
  else
    md5 -q "$path" > "$path.md5"
  fi
done
exec "$gradle" -p "$KNAPSHOTS_CHECKOUT/ci/bundled-adapter-smoke" test "-PknapshotsVersion=$version" --no-configuration-cache
