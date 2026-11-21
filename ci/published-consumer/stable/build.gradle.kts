plugins {
  kotlin("jvm") version "2.4.21"
  id("io.github.brennanium.knapshots.compatibility") version "0.1.0"
}

dependencies {
  testImplementation("io.github.brennanium:knapshots-snapshots:0.1.0")
  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}
tasks.test { useJUnitPlatform() }
