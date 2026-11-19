plugins {
  `java-library`
  kotlin("jvm") version "2.4.21"
  id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.knapshots"
version = "0.1.0-SNAPSHOT"

repositories { mavenCentral(); intellijPlatform { defaultRepositories() } }

dependencies {
  intellijPlatform {
    local(providers.gradleProperty("kpaths.idePath").orElse(providers.gradleProperty("knapshots.idePath")).orElse("/Applications/Android Studio.app"))
    testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform, configurationName = "compileOnly")
    testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
  }
  api("dev.knapshots:knapshots-editor:0.1.0-SNAPSHOT")
  compileOnly("junit:junit:4.13.2")
  testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(25) }
intellijPlatform { buildSearchableOptions = false; instrumentCode = false }
