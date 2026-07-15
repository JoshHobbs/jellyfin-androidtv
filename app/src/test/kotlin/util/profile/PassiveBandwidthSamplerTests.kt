package org.jellyfin.androidtv.util.profile

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Samples the link from real HLS segment downloads during playback. These are a better measurement
 * than any synthetic probe: they run on a warm keep-alive connection (so there is no TCP slow-start
 * to discard), they are real traffic on the real path, and they cost nothing. A segment is fetched
 * as fast as the link allows — it is already transcoded on disk, not metered out at the stream's
 * bitrate — so the rate reflects the link, and can push the ceiling back up when the link improves.
 */
class PassiveBandwidthSamplerTests : FunSpec({
	class Clock(var now: Long = 0L)

	fun sampler(store: BandwidthEstimateStore, clock: Clock) =
		PassiveBandwidthSampler(store, clock = { clock.now })

	test("ignores samples until the stream has been running a while") {
		val clock = Clock()
		val store = BandwidthEstimateStore()
		val sampler = sampler(store, clock)
		sampler.onStreamStarted()

		// At stream start the transcode is only just ahead of the player, so segment delivery is
		// throttled by the encoder rather than the link. Measuring here would under-read exactly the
		// way the old fixed-size probe did.
		clock.now = PassiveBandwidthSampler.START_GRACE_MS - 1
		sampler.onBandwidthSample(bytesTransferred = 500_000, bitrateEstimateBps = 3_000_000)
		store.ceilingBps(clock.now).shouldBeNull()

		clock.now = PassiveBandwidthSampler.START_GRACE_MS
		sampler.onBandwidthSample(bytesTransferred = 500_000, bitrateEstimateBps = 3_000_000)
		store.ceilingBps(clock.now) shouldBe 2_400_000 // 3_000_000 * 0.8
	}

	test("ignores transfers too small to mean anything") {
		val clock = Clock(PassiveBandwidthSampler.START_GRACE_MS)
		val store = BandwidthEstimateStore()
		val sampler = sampler(store, clock).also { it.onStreamStarted() }
		clock.now += PassiveBandwidthSampler.START_GRACE_MS

		sampler.onBandwidthSample(bytesTransferred = 1_000, bitrateEstimateBps = 3_000_000)
		store.ceilingBps(clock.now).shouldBeNull()
	}

	test("records at most one sample per throttle window") {
		val clock = Clock()
		val store = BandwidthEstimateStore()
		val sampler = sampler(store, clock).also { it.onStreamStarted() }

		clock.now = PassiveBandwidthSampler.START_GRACE_MS
		sampler.onBandwidthSample(500_000, 2_000_000)
		store.sampleCount(clock.now) shouldBe 1

		// a burst of segment completions right after must not flood the store
		repeat(10) {
			clock.now += 1_000
			sampler.onBandwidthSample(500_000, 2_000_000)
		}
		store.sampleCount(clock.now) shouldBe 1

		clock.now += PassiveBandwidthSampler.THROTTLE_MS
		sampler.onBandwidthSample(500_000, 2_000_000)
		store.sampleCount(clock.now) shouldBe 2
	}

	test("records nothing when no stream is running") {
		val clock = Clock(1_000_000L)
		val store = BandwidthEstimateStore()
		val sampler = sampler(store, clock)

		sampler.onBandwidthSample(500_000, 3_000_000)
		store.ceilingBps(clock.now).shouldBeNull()
	}

	test("a new stream re-applies the grace period") {
		val clock = Clock()
		val store = BandwidthEstimateStore()
		val sampler = sampler(store, clock).also { it.onStreamStarted() }

		clock.now = PassiveBandwidthSampler.START_GRACE_MS
		sampler.onBandwidthSample(500_000, 2_000_000)
		store.sampleCount(clock.now) shouldBe 1

		// a new stream starts: the encoder is catching up again, so hold off again
		sampler.onStreamStarted()
		clock.now += 1_000
		sampler.onBandwidthSample(500_000, 2_000_000)
		store.sampleCount(clock.now) shouldBe 1
	}

	test("a starved link is floored rather than recorded as absurdly low") {
		val clock = Clock()
		val store = BandwidthEstimateStore()
		val sampler = sampler(store, clock).also { it.onStreamStarted() }

		clock.now = PassiveBandwidthSampler.START_GRACE_MS
		sampler.onBandwidthSample(500_000, 1_000)
		store.ceilingBps(clock.now) shouldBe BandwidthDetector.MIN_BPS
	}
})
