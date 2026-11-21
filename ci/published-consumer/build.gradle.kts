import java.io.File

plugins {
  kotlin("jvm") version "2.4.21"
  id("io.github.brennanium.knapshots.compatibility")
}

val knapshotsVersion = providers.gradleProperty("knapshotsVersion").getOrElse("0.1.0-SNAPSHOT")

knapshots {
  compilerPlugin {
    artifact.set(layout.file(providers.environmentVariable("KNAPSHOTS_FIXTURE_COMPILER").map { File(it) }))
    id.set("dev.knapshots.fixture")
    firOption.set("fir")
    irOption.set("ir")
  }
}

dependencies {
  compileOnly("com.google.devtools.ksp:symbol-processing-api:2.3.12")
  testImplementation("io.github.brennanium:knapshots-compiler:${knapshotsVersion}")
  testImplementation("io.github.brennanium:knapshots-compiler-gradle:${knapshotsVersion}")
  testImplementation("io.github.brennanium:knapshots-ksp:${knapshotsVersion}")
  testImplementation("io.github.brennanium:knapshots-editor-junit:${knapshotsVersion}")
  testImplementation("io.github.brennanium:knapshots-bytecode:${knapshotsVersion}")
  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}
tasks.test { useJUnitPlatform() }
