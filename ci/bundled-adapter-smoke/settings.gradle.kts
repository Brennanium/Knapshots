pluginManagement {
  plugins { id("io.github.brennanium.knapshots.compatibility") version providers.gradleProperty("knapshotsVersion").getOrElse("0.1.0") }
  repositories {
    providers.environmentVariable("KNAPSHOTS_REPOSITORY").orNull?.let { maven { url = uri(it) } }
    gradlePluginPortal(); mavenCentral()
  }
}
dependencyResolutionManagement {
  repositories {
    providers.environmentVariable("KNAPSHOTS_REPOSITORY").orNull?.let { maven { url = uri(it) } }
    mavenCentral()
  }
}
rootProject.name = "bundled-adapter-smoke"
