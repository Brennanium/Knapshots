plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  api(project(":knapshots-editor"))
  api(libs.junit.jupiter)
  testImplementation(kotlin("test"))
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
  useJUnitPlatform()
}
