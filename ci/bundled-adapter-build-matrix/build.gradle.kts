plugins { id("io.github.brennanium.knapshots.compatibility") }
knapshotsCompatibility {
  editorDriver.set(layout.projectDirectory.file("editor-driver.sh"))
}
