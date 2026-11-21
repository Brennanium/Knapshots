plugins {
  kotlin("jvm") version "2.4.21"
  id("org.jetbrains.intellij.platform") version "2.20.1"
}
group = "io.github.brennanium"
version = providers.gradleProperty("knapshotsVersion").getOrElse("0.1.0-ci")
val checkout = rootProject.projectDir.resolve("../..").canonicalFile
repositories { mavenCentral(); intellijPlatform { defaultRepositories() } }
dependencies {
  implementation(files(listOf("knapshots-ide-runner", "knapshots-editor", "knapshots-snapshots").map {
    checkout.resolve("$it/build/libs/$it-$version.jar")
  }))
  intellijPlatform {
    local(providers.gradleProperty("knapshots.idePath").map(::file))
    bundledPlugin("org.jetbrains.kotlin")
  }
}
sourceSets.main {
  kotlin.srcDir(checkout.resolve("knapshots-intellij/src/main/kotlin"))
  resources.srcDir(checkout.resolve("knapshots-intellij/src/main/resources"))
}
kotlin { jvmToolchain(25) }
configurations.runtimeClasspath { exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib") }
intellijPlatform { buildSearchableOptions = false; instrumentCode = false }

// Fail instead of silently producing an incomplete plugin if preparation was skipped.
tasks.named("buildPlugin") {
  doFirst {
    listOf("knapshots-ide-runner", "knapshots-editor", "knapshots-snapshots").forEach {
      val jar = checkout.resolve("$it/build/libs/$it-$version.jar")
      require(jar.isFile) { "Build the runtime JAR first: $jar" }
    }
  }
}
