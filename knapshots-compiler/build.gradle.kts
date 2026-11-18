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

tasks.test {
  providers.gradleProperty("knapshots.kpathsCheckout").orNull?.let { systemProperty("knapshots.kpathsCheckout", it) }
  useJUnitPlatform()
}
