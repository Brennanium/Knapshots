plugins {
  alias(libs.plugins.kotlin.jvm)
}

val recorderTestClasspath = configurations.create("recorderTestClasspath")

dependencies {
  recorderTestClasspath(project(":knapshots-compiler"))
  api(project(":knapshots-snapshots"))
  testImplementation(kotlin("test"))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  dependsOn(":knapshots-compiler:jar")
  inputs.files(recorderTestClasspath).withPropertyName("editorRecorderClasspath")
  systemProperty("knapshots.editor.recorderClasspath", recorderTestClasspath.asPath)
  useJUnitPlatform()
}
