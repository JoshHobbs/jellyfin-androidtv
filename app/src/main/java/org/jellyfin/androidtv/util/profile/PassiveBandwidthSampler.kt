package org.jellyfin.androidtv.util.profile

import timber.log.Timber

/**
 * Feeds the link estimate from **real playback traffic** — ExoPlayer's measurement of the HLS
 * segment downloads it is already making.
 *
 * These are a better measurement than any synthetic probe. They run on a warm keep-alive
 * connection, so there is no TCP slow-start to discard — the very problem the probe has to engineer
 * around. They are real traffic on the real path, they cost nothing extra, and they arrive
 * constantly. And because a segment is fetched as fast as the link allows (it is already transcoded
 * on disk, not metered out at the stream's bitrate) the rate reflects the *link*, not the stream —
 * so it can discover the link is faster than the current ceiling and push it back up.
 *
 * Samples land in the same [BandwidthEstimateStore] the probe writes to, carrying the same
 * [BandwidthDetector.SAFETY_FACTOR], so everything in the store means the same thing: a ceiling
 * this link can hold. They only affect the *next* stream — the ceiling is fixed when playback is
 * negotiated, and re-negotiating mid-stream means tearing the player down, which is the stall this
 * whole mechanism exists to avoid.
 */
class PassiveBandwidthSampler(
	private val store: BandwidthEstimateStore,
	private val clock: () -> Long,
) {
	private var streamStartedAt: Long? = null
	private var lastRecordedAt: Long? = null

	/** A new stream is being prepared. Restarts the grace period. */
	fun onStreamStarted() {
		streamStartedAt = clock()
		lastRecordedAt = null
	}

	fun onStreamStopped() {
		streamStartedAt = null
	}

	/** One completed transfer, as reported by ExoPlayer's bandwidth meter. */
	fun onBandwidthSample(bytesTransferred: Long, bitrateEstimateBps: Long) {
		val startedAt = streamStartedAt ?: return
		val now = clock()

		// Early in a stream the transcode is only just ahead of the player, so segment delivery is
		// paced by the encoder rather than the link. Measuring here under-reads.
		if (now - startedAt < START_GRACE_MS) return

		// Too short a transfer to say anything about the link.
		if (bytesTransferred < MIN_TRANSFER_BYTES) return

		if (bitrateEstimateBps <= 0L) return

		// Segments complete every few seconds; without this a single film would bury the window in
		// hundreds of samples.
		val last = lastRecordedAt
		if (last != null && now - last < THROTTLE_MS) return
		lastRecordedAt = now

		val bps = BandwidthDetector.normalizeBps(bitrateEstimateBps)
		store.update(bps, now)
		Timber.i("Passive bandwidth sample: %d bps (from real segment traffic)", bps)
	}

	companion object {
		/** Long enough for the transcode to get ahead of the player. */
		const val START_GRACE_MS = 30_000L
		const val THROTTLE_MS = 30_000L
		const val MIN_TRANSFER_BYTES = 64_000L
	}
}
