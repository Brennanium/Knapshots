pluginManagement {
  plugins {
    id("io.github.brennanium.knapshots.compatibility") version providers.gradleProperty("knapshotsVersion").getOrElse("0.1.0-SNAPSHOT")
  }
  repositories {
    maven { url = uri(requireNotNull(providers.environmentVariable("KNAPSHOTS_REPOSITORY").orNull)) }
    gradlePluginPortal()
    mavenCentral()
  }
}
dependencyResolutionManagement {
  repositories {
    maven { url = uri(requireNotNull(providers.environmentVariable("KNAPSHOTS_REPOSITORY").orNull)) }
    mavenCentral()
  }
}
rootProject.name = "knapshots-published-consumer"

include(":compiler")
if (providers.gradleProperty("knapshotsVersion").orNull == "0.1.0") include(":stable")
