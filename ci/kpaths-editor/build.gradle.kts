plugins { id("dev.knapshots.compatibility") }

knapshotsCompatibility {
  editorDriver.set(layout.projectDirectory.file("editor-driver.sh"))
  useKefs.set(true)
  useXvfb.set(System.getenv("RUNNER_OS") == "Linux")
  scenarioJUnitXml.set(layout.projectDirectory.file("../../knapshots-ide-runner/build/test-results/test/TEST-dev.knapshots.testing.ide.KPathsEditorIntegrationTest.xml"))
}
