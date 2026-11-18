plugins { id("dev.knapshots.compatibility") }

knapshotsCompatibility {
  editorDriver.set(layout.projectDirectory.file("editor-driver.sh"))
  useKefs.set(true)
  useXvfb.set(System.getenv("RUNNER_OS") == "Linux")
}
