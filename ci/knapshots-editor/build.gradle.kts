plugins { id("dev.knapshots.compatibility") }
knapshotsCompatibility {
  editorDriver.set(layout.projectDirectory.file("editor-driver.sh"))
  useXvfb.set(System.getenv("RUNNER_OS") == "Linux")
  System.getenv("KNAPSHOTS_IDE_PATH")?.let { idePath.set(file(it)) }
}
