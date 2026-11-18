plugins {
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  api(project(":knapshots-editor"))
  testImplementation(project(":knapshots-editor-junit"))
  testImplementation(kotlin("test"))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
  useJUnitPlatform {
    if (!providers.gradleProperty("knapshots.includeIdeIntegration").isPresent) {
      excludeTags("ide-integration")
    }
  }
  systemProperty("knapshots.projectDir", rootProject.projectDir.absolutePath)
  providers.gradleProperty("knapshots.idePath").orNull?.let {
    systemProperty("knapshots.idePath", it)
  }
  listOf("kpathsCheckout", "kefsVersion", "kpathsMode", "reportDirectory").forEach { name ->
    providers.gradleProperty("knapshots.$name").orNull?.let { systemProperty("knapshots.$name", it) }
  }
}
