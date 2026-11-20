plugins {
  alias(libs.plugins.kotlin.jvm) apply false
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
