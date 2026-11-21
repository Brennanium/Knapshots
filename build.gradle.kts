plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  id("org.jetbrains.dokka") version "2.2.0"
}

group = "dev.knapshots"
version = providers.gradleProperty("VERSION_NAME").orElse("0.1.0-SNAPSHOT").get()

allprojects {
  group = rootProject.group
  version = rootProject.version
  // Self-tests should never rewrite the repository unless a test opts into recording explicitly.
  val selfTestRecording = providers.gradleProperty("knapshots.recording").orElse("none")
  tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("knapshots.compiler.record", selfTestRecording.get())
    systemProperty("knapshots.ksp.record", selfTestRecording.get())
    systemProperty("knapshots.editor.record", selfTestRecording.get())
  }
  // The launched IDE fixture runs Gradle from inside an already-running test JVM. Give that
  // nested invocation its own outputs so it cannot replace JARs on the outer test classpath.
  providers.gradleProperty("knapshots.isolatedBuildRoot").orNull?.let { isolatedRoot ->
    val relative = if (path == ":") "root" else path.removePrefix(":").replace(':', '/')
    layout.buildDirectory.set(rootProject.file(isolatedRoot).resolve(relative))
  }
}

// A local repository makes it possible to exercise the published artifacts from a separate build.
// Remote publication and signing are intentionally configured separately when a release is made.
val publicModules = setOf(
  "knapshots-snapshots", "knapshots-compiler", "knapshots-compiler-gradle", "knapshots-bytecode",
  "knapshots-ksp", "knapshots-editor", "knapshots-editor-junit", "knapshots-ide-runner",
  "knapshots-compatibility", "knapshots-gradle-plugin",
)
subprojects {
  if (name in publicModules) {
    pluginManager.apply("maven-publish")
    pluginManager.apply("org.jetbrains.dokka")
    extensions.configure<org.gradle.api.publish.PublishingExtension> {
      repositories {
        maven {
          name = "consumerVerification"
          url = rootProject.layout.buildDirectory.dir("consumer-repository").get().asFile.toURI()
        }
      }
      project.pluginManager.withPlugin("java") {
        project.extensions.configure<org.gradle.api.plugins.JavaPluginExtension> { withSourcesJar() }
        if (project.name != "knapshots-gradle-plugin") {
          publications.create<org.gradle.api.publish.maven.MavenPublication>("library") {
            from(components["java"])
          }
        }
      }
      publications.withType<org.gradle.api.publish.maven.MavenPublication>().configureEach {
        pom {
          name.set(project.name)
          description.set("Kotlin snapshot, compiler plugin, and editor testing tools")
          url.set("https://github.com/Brennanium/Knapshots")
          licenses { license { name.set("MIT License"); url.set("https://opensource.org/license/mit") } }
          scm {
            url.set("https://github.com/Brennanium/Knapshots")
            connection.set("scm:git:https://github.com/Brennanium/Knapshots.git")
            developerConnection.set("scm:git:ssh://git@github.com/Brennanium/Knapshots.git")
          }
        }
      }
    }
  }
}


val documentationVersion = providers.gradleProperty("documentationVersion").orElse("main")
val docsPython = providers.gradleProperty("docsPython").orElse("python3")
val docsSiteDirectory = layout.buildDirectory.dir("docs/site")

dokka {
  dokkaPublications.html {
    moduleName.set("Knapshots")
    moduleVersion.set(documentationVersion)
    outputDirectory.set(layout.buildDirectory.dir("docs/api"))
    suppressInheritedMembers.set(true)
    suppressObviousFunctions.set(true)
  }
}

dependencies {
  listOf("knapshots-snapshots", "knapshots-compiler", "knapshots-compiler-gradle", "knapshots-bytecode", "knapshots-ksp", "knapshots-editor", "knapshots-editor-junit", "knapshots-ide-runner", "knapshots-compatibility", "knapshots-gradle-plugin").forEach {
    dokka(project(":$it"))
  }
}

val mkdocsBuild = tasks.register<Exec>("mkdocsBuild") {
  group = "documentation"
  description = "Builds the Knapshots guides."
  workingDir = rootDir
  inputs.file("mkdocs.yml")
  inputs.dir("docs")
  outputs.dir(docsSiteDirectory)
  commandLine(docsPython.get(), "-m", "mkdocs", "build", "--strict", "--config-file", "mkdocs.yml", "--site-dir", docsSiteDirectory.get().asFile.absolutePath)
}

val copyDokkaApi = tasks.register<Sync>("copyDokkaApi") {
  dependsOn(mkdocsBuild, tasks.named("dokkaGenerate"))
  from(layout.buildDirectory.dir("docs/api"))
  into(docsSiteDirectory.map { it.dir("api") })
}

tasks.register<Exec>("docsBuild") {
  group = "documentation"
  description = "Builds the Knapshots guides and API reference."
  dependsOn(copyDokkaApi)
  workingDir = rootDir
  commandLine(docsPython.get(), "scripts/finalize-doc-site.py", docsSiteDirectory.get().asFile.absolutePath)
}

tasks.register<Exec>("docsServe") {
  group = "documentation"
  description = "Builds and serves the complete documentation site."
  dependsOn("docsBuild")
  commandLine(docsPython.get(), "-m", "http.server", "8000", "--directory", docsSiteDirectory.get().asFile.absolutePath)
}


/** Verify the artifacts and plugin marker from a separate, non-composite build. */
tasks.register<Exec>("verifyPublishedConsumer") {
  group = "verification"
  description = "Publishes locally and runs an independent Maven consumer."
  dependsOn(publicModules.map { ":$it:publishAllPublicationsToConsumerVerificationRepository" })
  dependsOn(":knapshots-test-compiler-plugin:jar")
  val consumerRepository = layout.buildDirectory.dir("consumer-repository")
  val compilerJar = project(":knapshots-test-compiler-plugin").layout.buildDirectory.file("libs/knapshots-test-compiler-plugin-${project.version}.jar")
  environment("KNAPSHOTS_REPOSITORY", consumerRepository.get().asFile.absolutePath)
  environment("KNAPSHOTS_FIXTURE_COMPILER", compilerJar.get().asFile.absolutePath)
  val wrapper = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "./gradlew"
  workingDir = rootDir
  commandLine(wrapper, "-p", "ci/published-consumer", "test", "--refresh-dependencies", "--no-daemon")
}
