plugins {
  alias(libs.plugins.kotlin.jvm) apply false
}

group = "dev.knapshots"
version = providers.gradleProperty("VERSION_NAME").orElse("0.1.0-SNAPSHOT").get()

allprojects {
  group = rootProject.group
  version = rootProject.version
}
