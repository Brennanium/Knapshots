plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-gradle-plugin`
}

dependencies {
  implementation(project(":knapshots-compatibility"))
  testImplementation(kotlin("test"))
  testImplementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:${libs.versions.kotlin.get()}")
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
  plugins {
    create("knapshotsCompatibility") {
      id = "dev.knapshots.compatibility"
      implementationClass = "dev.knapshots.gradle.KnapshotsCompatibilityPlugin"
    }
  }
}

val knapshotsVersion = project.version.toString()
tasks.processResources {
  inputs.property("knapshotsVersion", knapshotsVersion)
  expand(mapOf("knapshotsVersion" to knapshotsVersion))
}

tasks.test {
  systemProperty("knapshots.sourceRoot", rootProject.projectDir.absolutePath)
  useJUnitPlatform()
}
