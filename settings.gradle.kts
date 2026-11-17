pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
  }
}

rootProject.name = "Knapshots"

include(
  ":knapshots-snapshots",
  ":knapshots-compiler",
  ":knapshots-editor",
  ":knapshots-ide-runner",
)
