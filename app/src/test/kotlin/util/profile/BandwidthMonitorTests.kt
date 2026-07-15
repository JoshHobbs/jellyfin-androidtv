package org.jellyfin.androidtv.util.profile

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jellyfin.androidtv.auth.repository.Session
import org.jellyfin.androidtv.auth.repository.SessionRepository
import java.util.UUID

/**
 * The ceiling is a median of the recent samples, so it is only as good as the number of samples
 * behind it. The probe is noisy (readings from 0.4 to 1.9 Mbps have been seen on a link that
 * bulk-transfers 1.5-2.8), and a median over one or two samples cannot reject a bad reading — with
 * two, the conservative tie-break is just the minimum, so a single junk probe pins the ceiling far
 * too low for the whole window. So the monitor probes on a short warm-up interval until it holds
 * enough samples to outvote one outlier, and only then settles into the slow refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BandwidthMonitorTests : FunSpec({
	fun sessionRepository(): SessionRepository = mockk {
		every { currentSession } returns MutableStateFlow(
			Session(UUID.randomUUID(), UUID.randomUUID(), "token")
		)
	}

	fun countingDetector(counter: () -> Unit): BandwidthDetector = mockk<BandwidthDetector>().also {
		coEvery { it.detect() } answers { counter(); 1_000_000 }
	}

	fun monitor(
		detector: BandwidthDetector,
		store: BandwidthEstimateStore = BandwidthEstimateStore(),
		monitoringEnabled: Flow<Boolean> = flowOf(true),
		isPlaybackActive: () -> Boolean = { false },
		clock: () -> Long,
	) = BandwidthMonitor(
		sessionRepository(),
		detector,
		store,
		monitoringEnabled = monitoringEnabled,
		isPlaybackActive = isPlaybackActive,
		clock = clock,
	)

	test("probes on the short warm-up interval while the store is still cold") {
		runTest {
			var probes = 0
			val store = BandwidthEstimateStore()
			val monitor = monitor(countingDetector { probes++ }, store, clock = { currentTime })
			monitor.start(backgroundScope)

			runCurrent()
			probes shouldBe 1 // first probe lands immediately

			advanceTimeBy(BandwidthMonitor.WARMUP_INTERVAL_MS + 1)
			probes shouldBe 2

			advanceTimeBy(BandwidthMonitor.WARMUP_INTERVAL_MS + 1)
			probes shouldBe 3
		}
	}

	test("backs off to the slow refresh once the store holds enough samples") {
		runTest {
			var probes = 0
			val store = BandwidthEstimateStore()
			val monitor = monitor(countingDetector { probes++ }, store, clock = { currentTime })
			monitor.start(backgroundScope)

			// warm up until the store is no longer cold
			advanceTimeBy(BandwidthMonitor.WARMUP_INTERVAL_MS * BandwidthMonitor.WARMUP_SAMPLES + 1)
			store.sampleCount(currentTime) shouldBe BandwidthMonitor.WARMUP_SAMPLES
			val afterWarmup = probes

			// a warm-up-length wait must no longer be enough to trigger a probe
			advanceTimeBy(BandwidthMonitor.WARMUP_INTERVAL_MS * 2)
			probes shouldBe afterWarmup

			// ...but the full refresh interval is
			advanceTimeBy(BandwidthMonitor.REFRESH_INTERVAL_MS)
			probes shouldBe afterWarmup + 1
		}
	}

	test("never probes while a playback session is active") {
		runTest {
			var probes = 0
			val monitor = monitor(
				countingDetector { probes++ },
				isPlaybackActive = { true },
				clock = { currentTime },
			)
			monitor.start(backgroundScope)

			advanceTimeBy(BandwidthMonitor.REFRESH_INTERVAL_MS * 3)
			probes shouldBe 0
		}
	}

	test("probes only while foreground Auto monitoring is enabled") {
		runTest {
			var probes = 0
			val enabled = MutableStateFlow(false)
			val monitor = monitor(
				countingDetector { probes++ },
				monitoringEnabled = enabled,
				clock = { currentTime },
			)
			monitor.start(backgroundScope)

			advanceTimeBy(BandwidthMonitor.WARMUP_INTERVAL_MS * 2)
			probes shouldBe 0

			enabled.value = true
			runCurrent()
			probes shouldBe 1

			enabled.value = false
			advanceTimeBy(BandwidthMonitor.REFRESH_INTERVAL_MS)
			probes shouldBe 1
		}
	}

	test("cancels an in-flight probe when playback starts") {
		runTest {
			var playbackActive = false
			val started = CompletableDeferred<Unit>()
			val cancelled = CompletableDeferred<Unit>()
			val detector = mockk<BandwidthDetector>().also {
				coEvery { it.detect() } coAnswers {
					started.complete(Unit)
					try {
						awaitCancellation()
					} finally {
						cancelled.complete(Unit)
					}
				}
			}
			val monitor = monitor(
				detector,
				isPlaybackActive = { playbackActive },
				clock = { currentTime },
			)
			monitor.start(backgroundScope)
			runCurrent()
			started.isCompleted shouldBe true

			playbackActive = true
			advanceTimeBy(BandwidthMonitor.PLAYBACK_POLL_INTERVAL_MS)
			runCurrent()
			cancelled.isCompleted shouldBe true
		}
	}
})
