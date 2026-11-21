import dev.knapshots.testing.snapshot.assertSnapshot
import dev.knapshots.testing.snapshot.textSnapshotting
import org.junit.jupiter.api.Test

class StableConsumerTest {
  @Test fun `uses versioned dependencies from Maven`() {
    assertSnapshot("Hello, Ada!", "Hello, Ada!", textSnapshotting)
  }
}
