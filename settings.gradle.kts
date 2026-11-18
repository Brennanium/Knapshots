pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  // The opt-in IntelliJ adapter registers the selected local IDE as a repository through the
  // IntelliJ Platform Gradle plugin. Its metadata rule cannot be declared from settings.
  repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
  repositories {
    mavenCentral()
  }
}

rootProject.name = "Knapshots"

include(
  ":knapshots-snapshots",
  ":knapshots-compiler",
  ":knapshots-editor",
  ":knapshots-editor-junit",
  ":knapshots-ide-runner",
  ":knapshots-compatibility",
  ":knapshots-gradle-plugin",
)

// This module compiles against a selected local IDE installation. Keep it opt-in so normal
// snapshot/compiler/editor verification remains portable and does not require Android Studio.
if (providers.gradleProperty("knapshots.enableIntellijAdapter").isPresent) {
  include(":knapshots-intellij")
}
