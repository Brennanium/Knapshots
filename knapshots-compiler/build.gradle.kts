plugins {
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  api(project(":knapshots-snapshots"))
  implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
  testImplementation(kotlin("test"))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

val fixturePluginJar = project(":knapshots-test-compiler-plugin").layout.buildDirectory.file("libs/knapshots-test-compiler-plugin-${project.version}.jar")

tasks.test {
  dependsOn(":knapshots-test-compiler-plugin:jar")
  systemProperty("knapshots.fixturePluginJar", fixturePluginJar.get().asFile.absolutePath)
  providers.gradleProperty("knapshots.kpathsCheckout").orNull?.let { systemProperty("knapshots.kpathsCheckout", it) }
  useJUnitPlatform()
}
