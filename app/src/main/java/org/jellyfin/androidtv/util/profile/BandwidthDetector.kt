package org.jellyfin.androidtv.util.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.InputStream

/**
 * Measures downstream bandwidth from a streaming download, in two phases.
 *
 * The obvious way to do this — time a fixed-size download from the first byte — does not work on a
 * weak, high-latency link, which is precisely the link this exists for. Time-to-first-byte and TCP
 * slow-start dominate a short transfer: the connection spends several round trips ramping up, and a
 * probe that finishes during the ramp reports the ramp, not the link. Measured against a real
 * ~140 ms-RTT wifi link, a 256 KB probe read 4-8x *under* a 2 MB one taken seconds later.
 *
 * So: read and **discard** everything until the connection is warm ([WARMUP_MS], or
 * [WARMUP_MAX_BYTES] on a link fast enough to warm up sooner), then measure the steady state that
 * follows, stopping at [MEASURE_MAX_BYTES] or [MEASURE_MAX_MS]. One code path at every link speed —
 * a slow link still yields a full window of steady-state bytes, a fast one trips the byte cap early
 * — so there is no size branch to make the reading bimodal.
 *
 * Returns bits/sec with a [SAFETY_FACTOR] applied and floored at [MIN_BPS], or null if nothing could
 * be measured.
 *
 * @param openStream opens a download of [sizeBytes] bytes (production: the SDK bitrate-test
 *   endpoint, streamed). The detector closes it as soon as it has what it needs.
 * @param clock monotonic millisecond source (production: SystemClock.elapsedRealtime).
 * @param timeoutMs maximum probe duration. Cancellation closes the stream to unblock a pending read.
 */
class BandwidthDetector(
	private val openStream: suspend (sizeBytes: Int) -> InputStream,
	private val clock: () -> Long,
	private val timeoutMs: Long = TIMEOUT_MS,
) {
	suspend fun detect(): Int? = withTimeoutOrNull(timeoutMs) {
		try {
			openStream(REQUEST_SIZE_BYTES).use { stream ->
				measure(stream)
			}
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			Timber.w(error, "Bandwidth probe threw")
			null
		}
	}

	private suspend fun measure(stream: InputStream): Int? = coroutineScope {
		// Run the blocking loop in a child so cancellation can close the stream from this coroutine,
		// unblocking a pending read before structured concurrency waits for the child to finish.
		val measurement = async(Dispatchers.IO) { measureBlocking(stream) }
		try {
			measurement.await()
		} finally {
			if (!measurement.isCompleted) runCatching { stream.close() }
		}
	}

	private fun measureBlocking(stream: InputStream): Int? {
		val buffer = ByteArray(BUFFER_BYTES)

		// Read from [stream] until [maxMs] have elapsed since [base] or [maxBytes] have been
		// read, whichever comes first (or the stream ends). Returns the bytes read.
		fun drain(base: Long, maxMs: Long, maxBytes: Long): Long {
			var bytes = 0L
			while (clock() - base < maxMs && bytes < maxBytes) {
				val read = stream.read(buffer)
				if (read < 0) break
				bytes += read
			}
			return bytes
		}

		val start = clock()
		// Phase 1: warm up. Everything read here is discarded (see class doc).
		val warmupBytes = drain(start, WARMUP_MS, WARMUP_MAX_BYTES.toLong())

		// Phase 2: measure the steady state.
		val measureStart = clock()
		val measuredBytes = drain(measureStart, MEASURE_MAX_MS, MEASURE_MAX_BYTES.toLong())
		val measuredMs = clock() - measureStart

		val totalBytes = warmupBytes + measuredBytes
		val totalMs = clock() - start

		// The clock has millisecond resolution, so a link fast enough to finish the window
		// inside a single tick reads as zero elapsed. Floor the divisor at 1ms: such a link
		// is faster than we can time, and the result clamps to Int.MAX_VALUE anyway.
		val bps: Long? = when {
			measuredBytes >= MIN_MEASURED_BYTES ->
				measuredBytes * 8L * 1000L / measuredMs.coerceAtLeast(1L)

			// The stream ended before a steady-state sample could be taken (a very small
			// or truncated response). A poor estimate beats none: the store keeps a
			// median, so one weak reading gets outvoted rather than believed.
			totalBytes > 0L ->
				totalBytes * 8L * 1000L / totalMs.coerceAtLeast(1L)

			else -> null
		}

		// Worth keeping: if a reading ever looks wrong again, this says whether the warm-up
		// was skipped, how much steady state it actually saw, and over how long.
		Timber.d(
			"Bandwidth probe: discarded %d warm-up bytes, measured %d bytes over %d ms (%s bps raw)",
			warmupBytes, measuredBytes, measuredMs, bps,
		)

		return bps?.let { normalizeBps(it) }
	}

	companion object {
		/**
		 * Turns a raw measured link rate into a store-ready ceiling: back it off by [SAFETY_FACTOR],
		 * floor it at [MIN_BPS], and clamp into Int. Every value that reaches [BandwidthEstimateStore]
		 * — whether from this probe or the passive sampler — passes through here, so "a bandwidth
		 * sample" means one consistent thing regardless of source.
		 */
		fun normalizeBps(rawBps: Long): Int =
			(rawBps * SAFETY_FACTOR).toLong()
				.coerceIn(MIN_BPS.toLong(), Int.MAX_VALUE.toLong())
				.toInt()

		/**
		 * Asked of the server; far more than we intend to read. We stop at our own caps and close the
		 * stream, so this is an upper bound the link never reaches, not a download size.
		 */
		const val REQUEST_SIZE_BYTES = 20_000_000

		/** Discarded: TTFB and TCP slow-start live here. Several RTTs on a high-latency link. */
		const val WARMUP_MS = 1_000L

		/** ...unless the link is fast enough to warm up sooner, so we don't burn megabytes on it. */
		const val WARMUP_MAX_BYTES = 1_000_000

		const val MEASURE_MAX_MS = 8_000L
		const val MEASURE_MAX_BYTES = 4_000_000

		/** Below this, the measure window is too small to be a steady-state reading. */
		const val MIN_MEASURED_BYTES = 16_000

		const val BUFFER_BYTES = 8_192
		const val SAFETY_FACTOR = 0.8
		const val MIN_BPS = 250_000
		const val TIMEOUT_MS = 15_000L
	}
}
