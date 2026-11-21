plugins { id("io.github.brennanium.knapshots.compatibility") }
knapshotsCompatibility {
  editorDriver.set(layout.projectDirectory.file("../bundled-adapter-smoke/editor-driver.sh"))
  useKefs.set(true)
  useXvfb.set(System.getenv("RUNNER_OS") == "Linux")
}
