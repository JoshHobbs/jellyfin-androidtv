package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.ui.playback.PlaybackController.PlaybackState

/**
 * A bandwidth probe competes with the transcode stream, so it must only run when playback is truly
 * idle. The old guard used isPlaying() (== PLAYING), which is false while the player is BUFFERING,
 * SEEKING or PAUSED — so probes fired during a stall and under-read the link badly. A paused or
 * rebuffering player is still filling its (80s/240s) buffer, so it competes just as much.
 */
class PlaybackStateTests : FunSpec({
	test("a live playback session is active in every state that still moves data") {
		PlaybackState.PLAYING.isSessionActive() shouldBe true
		PlaybackState.BUFFERING.isSessionActive() shouldBe true
		PlaybackState.SEEKING.isSessionActive() shouldBe true
		PlaybackState.PAUSED.isSessionActive() shouldBe true
	}

	test("only a torn-down player counts as idle enough to probe") {
		PlaybackState.IDLE.isSessionActive() shouldBe false
		PlaybackState.UNDEFINED.isSessionActive() shouldBe false
		PlaybackState.ERROR.isSessionActive() shouldBe false
	}
})
