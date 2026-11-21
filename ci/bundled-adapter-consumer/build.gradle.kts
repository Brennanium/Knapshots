plugins {
  kotlin("jvm") version "2.4.21"
  id("io.github.brennanium.knapshots.compatibility")
}
fun env(name: String): String = requireNotNull(System.getenv(name)) { "Set $name for the bundled adapter consumer." }
val knapshotsVersion = providers.gradleProperty("knapshotsVersion").orElse("0.1.0").get()
val kpathsVersion = providers.gradleProperty("kpathsVersion").orElse("0.1.0").get()
knapshots {
  idePath.set(file(env("KNAPSHOTS_IDE_PATH")))
  wrapperRoot.set(file(env("KNAPSHOTS_WRAPPER_ROOT")))
  kotlinVersion.set("2.4.21")
  repository.set(file(env("KPATHS_REPOSITORY")))
  gradlePlugin { id.set("dev.kpaths.gradle-plugin"); version.set(kpathsVersion) }
  idePlugin { kefs {
    version.set(env("KNAPSHOTS_KEFS_VERSION"))
    idePlugin.set("io.github.brennanium:kpaths-compiler-plugin-jvm")
    repository.set(file(env("KPATHS_KEFS_REPOSITORY")))
    archive.set(file(env("KNAPSHOTS_KEFS_ARCHIVE")))
  } }
}
dependencies {
  testImplementation("io.github.brennanium:knapshots-editor-junit:$knapshotsVersion")
  testRuntimeOnly("io.github.brennanium:knapshots-ide-runner:$knapshotsVersion")
  testImplementation("io.github.brennanium:kpaths-core-jvm:$kpathsVersion")
  testImplementation("io.github.brennanium:kpaths-annotations-jvm:$kpathsVersion")
  testImplementation(kotlin("test")); testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}
tasks.test {
  useJUnitPlatform()
  systemProperty("knapshots.editor.preflightGradle", "true")
  systemProperty("knapshots.editor.temporaryParent", layout.buildDirectory.dir("editor-inspections").get().asFile.absolutePath)
  systemProperty("knapshots.editor.reportDirectory", layout.buildDirectory.dir("reports/editor-inspections").get().asFile.absolutePath)
}
