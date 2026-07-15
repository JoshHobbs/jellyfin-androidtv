package org.jellyfin.androidtv.util.profile

import java.util.UUID

/** Identity of the server path whose bandwidth is being measured. */
data class BandwidthEstimateScope(
	val serverId: UUID?,
	val networkHandle: Long?,
) {
	companion object {
		/** Stable scope for Android-free unit tests and callers that do not need invalidation. */
		val UNSCOPED = BandwidthEstimateScope(null, null)
	}
}

/**
 * Session cache of recent bandwidth measurements.
 *
 * The ceiling is the **median** measurement within a recent window ([WINDOW_MS]) — an estimate of
 * what the link *sustains*, not what it once managed. This used to take the highest sample in the
 * window, on the theory that keeping the upper rungs available let ABR climb back after a dip. On a
 * jittery link whose peak is roughly double its sustained rate that backfires badly: a single lucky
 * burst pins the ceiling above what the link can actually carry for the whole window, the top rung
 * of the ladder becomes unplayable, and the player climbs into it, starves, drops, and climbs again
 * — stalling every time round. The transcode ceiling has to be a rate the link can *hold*.
 *
 * A median (rather than a min) keeps one unlucky probe from sinking the ceiling for the whole
 * window, and still follows the link up when it genuinely improves. Where the sample count is even
 * the lower of the two middle values wins: over-estimating costs continuous rebuffering, while
 * under-estimating only costs some picture quality, so ties break toward the safe side.
 *
 * Measurements are session-only and scoped to the active server plus Android network handle. A
 * server or network change clears the window before it can be read or updated, so an estimate from a
 * fast path can never become the ceiling for a different, slower path.
 *
 * Thread-safe (@Synchronized): written from a background coroutine, read synchronously from the
 * device-profile build.
 */
class BandwidthEstimateStore(
	private val currentScope: () -> BandwidthEstimateScope? = { BandwidthEstimateScope.UNSCOPED },
) {
	private class Sample(val bps: Int, val atMs: Long)

	private val samples = ArrayDeque<Sample>()
	private var activeScope = currentScope()

	@Synchronized
	fun update(bps: Int, nowMs: Long) {
		if (synchronizeScope() == null) return
		record(bps, nowMs)
	}

	/** Captures the scope a probe is about to measure. */
	@Synchronized
	fun snapshotScope(): BandwidthEstimateScope? = synchronizeScope()

	/**
	 * Records a completed probe only if its server/network path is still current.
	 *
	 * This closes the race where Android changes networks while the download is in flight: the result
	 * belongs to [expectedScope], not whichever path happens to be active when the download finishes.
	 */
	@Synchronized
	fun updateIfScopeMatches(bps: Int, nowMs: Long, expectedScope: BandwidthEstimateScope): Boolean {
		if (synchronizeScope() != expectedScope) return false
		record(bps, nowMs)
		return true
	}

	private fun record(bps: Int, nowMs: Long) {
		samples.addLast(Sample(bps, nowMs))
		prune(nowMs)
	}

	/**
	 * Median measurement within [WINDOW_MS] of [nowMs], or null if there are none. On an even
	 * sample count this is the lower of the two middle values (see the class doc: ties break
	 * toward the safe side).
	 */
	@Synchronized
	fun ceilingBps(nowMs: Long): Int? {
		if (synchronizeScope() == null) return null
		prune(nowMs)
		if (samples.isEmpty()) return null
		val sorted = samples.map { it.bps }.sorted()
		return sorted[(sorted.size - 1) / 2]
	}

	/**
	 * How many measurements are currently inside the window. The monitor paces its probing off this:
	 * a median drawn from one or two samples cannot reject a bad reading, so it probes fast until
	 * there are enough of them.
	 */
	@Synchronized
	fun sampleCount(nowMs: Long): Int {
		if (synchronizeScope() == null) return 0
		prune(nowMs)
		return samples.size
	}

	private fun synchronizeScope(): BandwidthEstimateScope? {
		val scope = currentScope()
		if (scope != activeScope) {
			samples.clear()
			activeScope = scope
		}
		return scope
	}

	private fun prune(nowMs: Long) {
		while (samples.isNotEmpty() && nowMs - samples.first().atMs > WINDOW_MS) {
			samples.removeFirst()
		}
	}

	companion object {
		const val WINDOW_MS = 30 * 60 * 1000L
	}
}
