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

// Build a release-shaped runner only after building the IDE adapter. A separate Jar task
// avoids a cycle: buildPlugin itself needs the ordinary, unbundled runner JAR.
val releaseAdapter = providers.gradleProperty("knapshots.releaseAdapterArchive")
if (releaseAdapter.isPresent) {
  val bundledJar = tasks.register<Jar>("bundledEditorRunnerJar") {
    dependsOn(tasks.named("jar"))
    doFirst { require(file(releaseAdapter.get()).isFile) { "Editor adapter archive does not exist: ${releaseAdapter.get()}" } }
    archiveClassifier.set("bundled-adapter")
    from(zipTree(tasks.named<Jar>("jar").flatMap { it.archiveFile }))
    from(file(releaseAdapter.get())) {
      into("dev/knapshots/testing/ide")
      rename { "editor-adapter.zip" }
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  }
  afterEvaluate { extensions.configure<org.gradle.api.publish.PublishingExtension> {
    publications.named<org.gradle.api.publish.maven.MavenPublication>("library") {
      val original = artifacts.single { it.extension == "jar" && it.classifier == null }
      artifacts.remove(original)
      artifact(bundledJar) { classifier = null }
    }
  } }
  tasks.withType<org.gradle.api.publish.tasks.GenerateModuleMetadata>().configureEach { enabled = false }
}
