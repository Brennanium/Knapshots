plugins {
  kotlin("jvm") version "2.4.21"
  `java-gradle-plugin`
  `maven-publish`
}
group = "dev.knapshots.testing"
version = "1.0.0"
repositories { mavenCentral() }
dependencies { compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.4.21") }
gradlePlugin {
  plugins { create("fixture") {
    id = "dev.knapshots.testing.fixture"
    implementationClass = "dev.knapshots.fixture.FixtureGradlePlugin"
  } }
}
publishing { repositories { maven { name = "fixture"; url = uri(requireNotNull(System.getenv("KNAPSHOTS_FIXTURE_REPOSITORY"))) } } }
