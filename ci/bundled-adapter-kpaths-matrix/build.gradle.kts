plugins { id("io.github.brennanium.knapshots.compatibility") }
knapshotsCompatibility {
  editorDriver.set(layout.projectDirectory.file("../bundled-adapter-consumer/editor-driver.sh"))
  useKefs.set(true)
  useXvfb.set(System.getenv("RUNNER_OS") == "Linux")
}
