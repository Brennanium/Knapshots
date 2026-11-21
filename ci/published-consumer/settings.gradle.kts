pluginManagement {
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
