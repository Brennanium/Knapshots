plugins {
  kotlin("jvm") version "2.4.21"
  id("io.github.brennanium.knapshots.compatibility")
}
val knapshotsVersion = providers.gradleProperty("knapshotsVersion").orElse("0.1.0").get()
knapshots {
  idePath.set(file(requireNotNull(System.getenv("KNAPSHOTS_IDE_PATH"))))
  wrapperRoot.set(file(requireNotNull(System.getenv("KNAPSHOTS_WRAPPER_ROOT"))))
  kotlinVersion.set("2.4.21")
}
dependencies {
  testImplementation("io.github.brennanium:knapshots-editor-junit:$knapshotsVersion")
  testRuntimeOnly("io.github.brennanium:knapshots-ide-runner:$knapshotsVersion")
  testImplementation(kotlin("test")); testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}
tasks.test {
  useJUnitPlatform()
  systemProperty("knapshots.editor.preflightGradle", "true")
  systemProperty("knapshots.editor.temporaryParent", layout.buildDirectory.dir("editor-inspections").get().asFile.absolutePath)
  systemProperty("knapshots.editor.reportDirectory", layout.buildDirectory.dir("reports/editor-inspections").get().asFile.absolutePath)
}
