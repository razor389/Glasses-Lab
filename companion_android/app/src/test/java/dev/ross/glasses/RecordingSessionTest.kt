package dev.ross.glasses

import org.junit.Assert.*
import org.junit.Test

class RecordingSessionTest {
  @Test fun authoritativeStatusRecoversUnconfirmedStartWithoutStartingAgain() {
    val session = RecordingSession()
    session.restoreVideo("button-video")
    session.started("unrelated-video")
    assertEquals(RecordingSession.Phase.UNCERTAIN, session.phase)
    session.started("button-video")
    assertEquals(RecordingSession.Phase.RECORDING, session.phase)
    assertEquals("button-video", session.id)
    assertEquals("button-video", session.stopping())
  }
  @Test fun lostVideoAckRetainsTheIdNeededToStop() {
    val session = RecordingSession()
    session.begin(RecordingSession.Kind.VIDEO, "video-one")
    session.uncertain("video-one")
    assertEquals("video-one", session.stopping())
    assertTrue(session.finish("video-one"))
    assertTrue(session.idle)
  }
  @Test fun oldCompletionCannotFinishNewRecording() {
    val session = RecordingSession()
    session.begin(RecordingSession.Kind.VIDEO, "old")
    session.finish("old")
    session.begin(RecordingSession.Kind.AUDIO, "new")
    assertFalse(session.finish("old"))
    assertEquals("new", session.id)
  }
  @Test fun delayedStartDoesNotUndoStop() {
    val session = RecordingSession()
    session.begin(RecordingSession.Kind.VIDEO, "video")
    session.stopping()
    session.started("video")
    assertEquals(RecordingSession.Phase.STOPPING, session.phase)
  }
  @Test fun restoredVideoCanBeStoppedAfterAppRestart() {
    val session = RecordingSession()
    session.restoreVideo("saved-video")
    assertEquals(RecordingSession.Kind.VIDEO, session.kind)
    assertEquals(RecordingSession.Phase.UNCERTAIN, session.phase)
    assertEquals("saved-video", session.stopping())
  }
  @Test(expected = IllegalStateException::class) fun simultaneousAudioAndVideoAreRejected() {
    val session = RecordingSession()
    session.begin(RecordingSession.Kind.AUDIO, "audio")
    session.begin(RecordingSession.Kind.VIDEO, "video")
  }
  @Test fun pausedAudioRetainsIdentityUntilExplicitStop() {
    val session = RecordingSession()
    session.begin(RecordingSession.Kind.AUDIO, "audio")
    session.started("audio")
    session.stopping()
    session.pause("audio", false)
    session.started("audio") // a late microphone frame is not a Resume command
    assertEquals(RecordingSession.Phase.PAUSED, session.phase)
    session.resume()
    session.started("audio")
    assertEquals(RecordingSession.Phase.RECORDING, session.phase)
    session.stopping()
    session.finish("audio")
    session.pause("audio", true)
    assertTrue(session.idle)
  }
  @Test(expected = IllegalStateException::class) fun stoppedAudioCannotResume() {
    val session = RecordingSession()
    session.begin(RecordingSession.Kind.AUDIO, "audio")
    session.finish("audio")
    session.resume()
  }
}
