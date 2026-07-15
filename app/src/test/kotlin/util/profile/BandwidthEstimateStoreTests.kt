package org.jellyfin.androidtv.util.profile

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

class BandwidthEstimateStoreTests : FunSpec({
	test("ceiling is null before any update") {
		BandwidthEstimateStore().ceilingBps(0L).shouldBeNull()
	}

	test("a single measurement is used as-is") {
		val store = BandwidthEstimateStore()
		store.update(1_550_000, 0L)
		store.ceilingBps(0L) shouldBe 1_550_000
	}

	// The bug this replaces: ceilingBps() returned the HIGHEST sample in the window. On a link whose
	// peak is ~2x its sustained rate, one lucky burst pinned the ceiling above what the link could
	// actually carry — for a full 30 minutes. The top ladder rung was then unplayable, and the player
	// climbed into it, starved, dropped, and climbed again. The ceiling must track the SUSTAINED rate.
	test("a lucky peak does not set the ceiling") {
		val store = BandwidthEstimateStore()
		store.update(1_570_000, 0L)
		store.update(2_180_000, 60_000L)
		store.update(2_820_000, 120_000L) // the lucky peak
		store.ceilingBps(120_000L) shouldBe 2_180_000
	}

	test("a single bad dip does not sink the ceiling") {
		val store = BandwidthEstimateStore()
		store.update(2_000_000, 0L)
		store.update(2_100_000, 60_000L)
		store.update(250_000, 120_000L) // one bad probe
		store.ceilingBps(120_000L) shouldBe 2_000_000
	}

	test("an even number of samples resolves to the lower middle, staying conservative") {
		val store = BandwidthEstimateStore()
		store.update(1_000_000, 0L)
		store.update(3_000_000, 60_000L)
		store.ceilingBps(60_000L) shouldBe 1_000_000
	}

	// The monitor paces itself off this: a median built from one or two samples is not a median at
	// all (with two, the conservative tie-break degenerates to the minimum), so it probes fast until
	// the store holds enough to outvote a single bad reading.
	test("sample count reports how many measurements are in the window") {
		val store = BandwidthEstimateStore()
		store.sampleCount(0L) shouldBe 0
		store.update(1_000_000, 0L)
		store.update(1_200_000, 1_000L)
		store.sampleCount(1_000L) shouldBe 2
	}

	test("sample count ignores measurements that have aged out") {
		val store = BandwidthEstimateStore()
		store.update(1_000_000, 0L)
		store.update(1_200_000, 1_000L)
		store.sampleCount(BandwidthEstimateStore.WINDOW_MS + 2_000L) shouldBe 0
	}

	test("measurements older than the window are dropped") {
		val store = BandwidthEstimateStore()
		store.update(1_550_000, 0L)
		store.update(250_000, BandwidthEstimateStore.WINDOW_MS + 1L)
		store.ceilingBps(BandwidthEstimateStore.WINDOW_MS + 1L) shouldBe 250_000
	}

	test("a burst of good probes does not immediately raise the ceiling") {
		val store = BandwidthEstimateStore()
		store.update(1_000_000, 0L)
		store.update(1_100_000, 60_000L)
		store.update(900_000, 120_000L)
		store.ceilingBps(120_000L) shouldBe 1_000_000

		// The link starts reading fast. The ceiling must NOT leap to it: on a jittery link that is
		// exactly what a lucky burst looks like, and committing the transcode to it is what caused
		// the stalling. Good samples have to outweigh the weak ones first.
		store.update(4_000_000, 180_000L)
		store.update(4_200_000, 240_000L)
		store.update(4_100_000, 300_000L)
		store.ceilingBps(300_000L) shouldBe 1_100_000
	}

	test("measurements are cleared when the active server changes") {
		var scope = BandwidthEstimateScope(UUID.randomUUID(), 1L)
		val store = BandwidthEstimateStore(currentScope = { scope })
		store.update(20_000_000, 0L)
		store.ceilingBps(0L) shouldBe 20_000_000

		scope = BandwidthEstimateScope(UUID.randomUUID(), 1L)
		store.ceilingBps(1L).shouldBeNull()
	}

	test("measurements are cleared when the active network changes") {
		val serverId = UUID.randomUUID()
		var scope = BandwidthEstimateScope(serverId, 1L)
		val store = BandwidthEstimateStore(currentScope = { scope })
		store.update(20_000_000, 0L)

		scope = BandwidthEstimateScope(serverId, 2L)
		store.ceilingBps(1L).shouldBeNull()
	}

	test("a probe result is rejected when the network changes in flight") {
		val serverId = UUID.randomUUID()
		var scope = BandwidthEstimateScope(serverId, 1L)
		val store = BandwidthEstimateStore(currentScope = { scope })
		val probeScope = store.snapshotScope()!!

		scope = BandwidthEstimateScope(serverId, 2L)
		store.updateIfScopeMatches(20_000_000, 1L, probeScope) shouldBe false
		store.ceilingBps(1L).shouldBeNull()
	}

	test("the ceiling does follow the link up once the weak samples age out") {
		val store = BandwidthEstimateStore()
		store.update(1_000_000, 0L)
		store.update(1_100_000, 60_000L)
		store.update(900_000, 120_000L)
		store.update(4_000_000, 180_000L)
		store.update(4_200_000, 240_000L)
		store.update(4_100_000, 300_000L)

		// far enough ahead that the three weak samples have fallen out of the window
		val now = BandwidthEstimateStore.WINDOW_MS + 121_000L
		store.ceilingBps(now) shouldBe 4_100_000
	}
})
