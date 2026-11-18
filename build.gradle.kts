plugins {
  alias(libs.plugins.kotlin.jvm) apply false
}

group = "dev.knapshots"
version = providers.gradleProperty("VERSION_NAME").orElse("0.1.0-SNAPSHOT").get()

allprojects {
  group = rootProject.group
  version = rootProject.version
  // The launched IDE fixture runs Gradle from inside an already-running test JVM. Give that
  // nested invocation its own outputs so it cannot replace JARs on the outer test classpath.
  providers.gradleProperty("knapshots.isolatedBuildRoot").orNull?.let { isolatedRoot ->
    val relative = if (path == ":") "root" else path.removePrefix(":").replace(':', '/')
    layout.buildDirectory.set(rootProject.file(isolatedRoot).resolve(relative))
  }
}
