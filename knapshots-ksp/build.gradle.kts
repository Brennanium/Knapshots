plugins {
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  api(project(":knapshots-compiler"))
  implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
  implementation(gradleTestKit())
  testImplementation(kotlin("test"))
  testImplementation("com.google.devtools.ksp:symbol-processing-api:${libs.versions.ksp.get()}")
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  dependsOn(":knapshots-test-ksp-processor:jar")
  inputs.file(project(":knapshots-test-ksp-processor").layout.buildDirectory.file("libs/knapshots-test-ksp-processor-${project.version}.jar"))
  systemProperty("knapshots.fixtureKspJar", project(":knapshots-test-ksp-processor").layout.buildDirectory.file("libs/knapshots-test-ksp-processor-${project.version}.jar").get().asFile.absolutePath)
  systemProperty("knapshots.fixtureKspVersion", libs.versions.ksp.get())
  useJUnitPlatform()
}
