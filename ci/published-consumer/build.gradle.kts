import java.io.File

plugins {
  kotlin("jvm") version "2.4.21"
  id("dev.knapshots.compatibility") version "0.1.0-SNAPSHOT"
}

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
  testImplementation("dev.knapshots:knapshots-compiler:0.1.0-SNAPSHOT")
  testImplementation("dev.knapshots:knapshots-compiler-gradle:0.1.0-SNAPSHOT")
  testImplementation("dev.knapshots:knapshots-ksp:0.1.0-SNAPSHOT")
  testImplementation("dev.knapshots:knapshots-editor-junit:0.1.0-SNAPSHOT")
  testImplementation("dev.knapshots:knapshots-bytecode:0.1.0-SNAPSHOT")
  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}
tasks.test { useJUnitPlatform() }
