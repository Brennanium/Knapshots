pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "knapshots-intellij-fixture"

includeBuild("..") { name = "Knapshots" }
