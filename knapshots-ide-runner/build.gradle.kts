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
  useJUnitPlatform {
    if (!providers.gradleProperty("knapshots.includeIdeIntegration").isPresent) {
      excludeTags("ide-integration")
    }
  }
  systemProperty("knapshots.projectDir", rootProject.projectDir.absolutePath)
  providers.gradleProperty("knapshots.idePath").orNull?.let {
    systemProperty("knapshots.idePath", it)
  }
}
