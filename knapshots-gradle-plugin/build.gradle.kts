plugins {
  alias(libs.plugins.kotlin.jvm)
  `java-gradle-plugin`
}

dependencies {
  implementation(project(":knapshots-compatibility"))
  testImplementation(kotlin("test"))
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

tasks.test { useJUnitPlatform() }
