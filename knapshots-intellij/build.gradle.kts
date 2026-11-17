plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.intellij.platform)
}

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  implementation(project(":knapshots-ide-runner"))
  intellijPlatform {
    local(providers.gradleProperty("knapshots.idePath").map(::file))
    bundledPlugin("org.jetbrains.kotlin")
  }
}

kotlin {
  jvmToolchain(25)
}

configurations.runtimeClasspath {
  exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

intellijPlatform {
  buildSearchableOptions = false
  instrumentCode = false
}
