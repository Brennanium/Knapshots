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

tasks.test { useJUnitPlatform() }
