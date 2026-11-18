import java.util.Properties

plugins {
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  api(project(":knapshots-editor"))
  testImplementation(project(":knapshots-editor-junit"))
  testImplementation(kotlin("test"))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
  useJUnitPlatform {
    if (!providers.gradleProperty("knapshots.includeIdeIntegration").isPresent) {
      excludeTags("ide-integration")
    }
  }
  systemProperty("knapshots.projectDir", rootProject.projectDir.absolutePath)
  providers.gradleProperty("knapshots.kpathsCheckout").orNull?.let { checkout ->
    val kpaths = file(checkout)
    val properties = Properties().apply { kpaths.resolve("gradle.properties").inputStream().use(::load) }
    val version = checkNotNull(properties.getProperty("VERSION_NAME"))
    val config = mapOf(
      "wrapperRoot" to providers.gradleProperty("knapshots.editor.wrapperRoot").orElse(rootProject.projectDir.absolutePath).get(),
      "idePath" to providers.gradleProperty("knapshots.idePath").get(),
      "kotlinVersion" to libs.versions.kotlin.get(),
      "pluginId" to "dev.kpaths.gradle-plugin",
      "pluginVersion" to version,
      "repository" to kpaths.resolve("build/consumer-verification/repository").absolutePath,
      "kefsVersion" to providers.gradleProperty("knapshots.kefsVersion").get(),
      "kefsRepository" to kpaths.resolve("kpaths-kefs-ide-fixture/build/kefs-repository").absolutePath,
      "kefsCoordinate" to "io.github.brennanium:kpaths-compiler-plugin-jvm",
      "kefsEnabled" to (providers.gradleProperty("knapshots.kpathsMode").orElse("enabled").get() != "disabled").toString(),
      "temporaryParent" to rootProject.layout.buildDirectory.dir("ide-integration").get().asFile.absolutePath,
    )
    // Model the consumer's ordinary test dependencies as the temporary project's classpath.
    val editorDependencies = files(
      kpaths.resolve("kpaths-core/build/libs/kpaths-core-jvm-$version.jar"),
      kpaths.resolve("kpaths-annotations/build/libs/kpaths-annotations-jvm-$version.jar"),
    )
    classpath += editorDependencies
    jvmArgumentProviders.add(CommandLineArgumentProvider {
      listOf("-Dknapshots.editor.classpath=${editorDependencies.asPath}")
    })
    config.forEach { (key, value) -> systemProperty("knapshots.editor.$key", value) }
    if (!providers.gradleProperty("knapshots.editor.adapterArchive").isPresent) {
      systemProperty("knapshots.editor.runnerRoot", rootProject.projectDir.absolutePath)
    }
    listOf("adapterArchive", "kefsArchive", "wrapperRoot").forEach { name ->
      providers.gradleProperty("knapshots.editor.$name").orNull?.let { systemProperty("knapshots.editor.$name", it) }
    }
    providers.gradleProperty("knapshots.reportDirectory").orNull?.let { systemProperty("knapshots.editor.reportDirectory", it) }
  }
  providers.gradleProperty("knapshots.idePath").orNull?.let {
    systemProperty("knapshots.idePath", it)
  }
  listOf("kpathsCheckout", "kefsVersion", "kpathsMode", "reportDirectory").forEach { name ->
    providers.gradleProperty("knapshots.$name").orNull?.let { systemProperty("knapshots.$name", it) }
  }
}
