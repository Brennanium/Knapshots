plugins {
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  api(project(":knapshots-editor"))
  testImplementation(kotlin("test"))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  useJUnitPlatform()
}
