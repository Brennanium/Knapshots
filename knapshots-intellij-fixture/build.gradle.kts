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
    bundledPlugin("com.intellij.java")
    bundledPlugin("org.jetbrains.kotlin")
    testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform, configurationName = "compileOnly")
    testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
  }
  api("dev.knapshots:knapshots-editor:0.1.0-SNAPSHOT")
  compileOnly("junit:junit:4.13.2")
  testImplementation(kotlin("test"))
}

// Test the real fixture plugin against the Kotlin compiler bundled in the selected IDE.
val ideFixturePlugin = sourceSets.create("ideFixturePlugin") {
  kotlin.srcDir("../knapshots-test-compiler-plugin/src/main/kotlin")
  resources.srcDir("../knapshots-test-compiler-plugin/src/main/resources")
  compileClasspath += sourceSets.main.get().compileClasspath
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileIdeFixturePluginKotlin") {
  compilerOptions.freeCompilerArgs.add("-opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
}
val ideFixturePluginJar = tasks.register<Jar>("ideFixturePluginJar") {
  archiveFileName = "knapshots-fixture-ide-compiler-plugin.jar"
  from(ideFixturePlugin.output)
}
val buildFixturePlugin = gradle.includedBuild("Knapshots").task(":knapshots-test-compiler-plugin:jar")
val ideKotlinStdlib = configurations.create("ideKotlinStdlib") { isTransitive = false }
dependencies { ideKotlinStdlib("org.jetbrains.kotlin:kotlin-stdlib:2.4.21") }
tasks.test {
  dependsOn(buildFixturePlugin, ideFixturePluginJar)
  val buildPlugin = file("../knapshots-test-compiler-plugin/build/libs/knapshots-test-compiler-plugin-0.1.0-SNAPSHOT.jar")
  inputs.file(buildPlugin)
  inputs.file(ideFixturePluginJar.flatMap { it.archiveFile })
  systemProperty("knapshots.fixtureKotlinStdlib", ideKotlinStdlib.singleFile.absolutePath)
  systemProperty("knapshots.fixtureBuildPlugin", buildPlugin.absolutePath)
  systemProperty("knapshots.fixtureIdePlugin", ideFixturePluginJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
}

kotlin { jvmToolchain(25) }
intellijPlatform { buildSearchableOptions = false; instrumentCode = false }
