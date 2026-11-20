plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
  api(project(":knapshots-snapshots"))
  api(project(":knapshots-compiler"))
  implementation("org.ow2.asm:asm:9.7.1")
  implementation("org.ow2.asm:asm-util:9.7.1")
  testImplementation(kotlin("test"))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  dependsOn(":knapshots-test-compiler-plugin:jar")
  inputs.file(project(":knapshots-test-compiler-plugin").layout.buildDirectory.file("libs/knapshots-test-compiler-plugin-${project.version}.jar"))
  systemProperty("knapshots.fixturePluginJar", project(":knapshots-test-compiler-plugin").layout.buildDirectory.file("libs/knapshots-test-compiler-plugin-${project.version}.jar").get().asFile.absolutePath)
  useJUnitPlatform()
}
