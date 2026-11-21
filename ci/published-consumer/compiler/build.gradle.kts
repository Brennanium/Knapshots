plugins {
  kotlin("jvm") version "2.4.21"
  id("dev.knapshots.compatibility") version "0.1.0-SNAPSHOT"
}
dependencies {
  compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")
  testImplementation("dev.knapshots:knapshots-compiler:0.1.0-SNAPSHOT")
  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
  compilerOptions.freeCompilerArgs.add("-opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
}
tasks.test { useJUnitPlatform() }
