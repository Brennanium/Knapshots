plugins {
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  implementation("org.apache.commons:commons-compress:1.27.1")
  testImplementation(kotlin("test"))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }
