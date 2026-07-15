package org.jellyfin.androidtv.util.profile

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeBetween
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A clock the fake link drives forward as bytes are transferred. Kept in nanoseconds internally:
 * accumulating per-chunk durations in whole milliseconds truncates badly on a fast link (8 KB at
 * 50 Mbps is 1.31 ms, which would round to 1 and report the link as 65 Mbps).
 */
private class FakeClock {
	private var nanos = 0L
	val now get() = nanos / 1_000_000L
	fun advanceNanos(ns: Long) { nanos += ns }
}

/**
 * A link that delivers [totalBytes] at a throughput that may change over time, advancing [clock] by
 * however long each chunk would really have taken. [bpsAt] lets a test script TCP slow-start: slow
 * for the first stretch, then steady.
 */
private class FakeLink(
	private val clock: FakeClock,
	private val totalBytes: Long,
	private val bpsAt: (elapsedMs: Long) -> Long,
) : InputStream() {
	var sent = 0L
		private set

	override fun read(b: ByteArray, off: Int, len: Int): Int {
		if (sent >= totalBytes) return -1
		val n = minOf(len.toLong(), totalBytes - sent).toInt()
		clock.advanceNanos(n.toLong() * 8L * 1_000_000_000L / bpsAt(clock.now))
		sent += n
		return n
	}

	override fun read(): Int = throw UnsupportedOperationException("read into a buffer")
}

private class CloseUnblocksInputStream : InputStream() {
	private val releaseRead = CountDownLatch(1)
	val closed = AtomicBoolean(false)

	override fun read(b: ByteArray, off: Int, len: Int): Int {
		releaseRead.await()
		return -1
	}

	override fun read(): Int = throw UnsupportedOperationException("read into a buffer")

	override fun close() {
		closed.set(true)
		releaseRead.countDown()
	}
}

class BandwidthDetectorTests : FunSpec({
	fun detector(link: FakeLink, clock: FakeClock) = BandwidthDetector(
		openStream = { link },
		clock = { clock.now },
	)

	// The bug this design removes. The old probe timed a fixed-size download from byte zero, so on a
	// high-RTT link the reading was dominated by TTFB and TCP slow-start and under-read by 4-8x.
	// Here the link crawls at 300 kbps for its first second and then settles at 2.5 Mbps: the
	// detector must report the 2.5, not the blend.
	test("discards TCP slow-start and reports the steady-state rate") {
		val clock = FakeClock()
		val link = FakeLink(clock, totalBytes = 50_000_000) { elapsed ->
			if (elapsed < 1_000L) 300_000L else 2_500_000L
		}
		val result = runBlocking { detector(link, clock).detect() }
		// 2_500_000 * 0.8 safety factor = 2_000_000
		result!!.shouldBeBetween(1_900_000, 2_100_000)
	}

	test("a genuinely slow link is measured accurately, not thrown away") {
		val clock = FakeClock()
		val link = FakeLink(clock, totalBytes = 50_000_000) { 400_000L }
		val result = runBlocking { detector(link, clock).detect() }
		// 400_000 * 0.8 = 320_000
		result!!.shouldBeBetween(305_000, 335_000)
	}

	test("a fast link stops at the byte cap instead of downloading forever") {
		val clock = FakeClock()
		val link = FakeLink(clock, totalBytes = 500_000_000) { 50_000_000L }
		val result = runBlocking { detector(link, clock).detect() }
		result!!.shouldBeBetween(38_000_000, 42_000_000) // 50 Mbps * 0.8
		// warm-up is byte-bounded on a fast link, and the measure window is capped
		link.sent.toInt().shouldBeLessThanOrEqual(
			BandwidthDetector.WARMUP_MAX_BYTES + BandwidthDetector.MEASURE_MAX_BYTES + 100_000
		)
	}

	test("falls back to the overall average when the stream ends during warm-up") {
		val clock = FakeClock()
		// ends well inside the warm-up window, so no steady-state sample is ever taken
		val link = FakeLink(clock, totalBytes = 32_768) { 600_000L }
		val result = runBlocking { detector(link, clock).detect() }
		// 600_000 * 0.8 = 480_000 from the overall average
		result!!.shouldBeBetween(465_000, 495_000)
	}

	test("a tiny measurement is clamped up to the floor") {
		val clock = FakeClock()
		val link = FakeLink(clock, totalBytes = 50_000_000) { 100_000L }
		runBlocking { detector(link, clock).detect() } shouldBe BandwidthDetector.MIN_BPS
	}

	test("a very fast link clamps to Int.MAX_VALUE instead of overflowing") {
		val clock = FakeClock()
		val link = FakeLink(clock, totalBytes = 5_000_000_000) { 100_000_000_000L }
		runBlocking { detector(link, clock).detect() } shouldBe Int.MAX_VALUE
	}

	test("returns null when opening the stream throws") {
		val detector = BandwidthDetector(
			openStream = { throw RuntimeException("network down") },
			clock = { 0L },
		)
		runBlocking { detector.detect() }.shouldBeNull()
	}

	test("timeout closes a blocked stream so the detector actually returns") {
		val stream = CloseUnblocksInputStream()
		val detector = BandwidthDetector(
			openStream = { stream },
			clock = { 0L },
			timeoutMs = 50L,
		)

		runBlocking { detector.detect() }.shouldBeNull()
		stream.closed.get() shouldBe true
	}
})
