package org.jellyfin.androidtv.util.profile

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MaxBitrateResolverTests : FunSpec({
	test("auto sentinel with a measurement returns the measurement") {
		MaxBitrateResolver.resolveBps("auto", 1_500_000) shouldBe 1_500_000
	}
	test("auto sentinel without a measurement returns the conservative fallback") {
		MaxBitrateResolver.resolveBps("auto", null) shouldBe MaxBitrateResolver.FALLBACK_BPS
	}
	test("a manual mbit value is converted to bps") {
		MaxBitrateResolver.resolveBps("4", null) shouldBe 4_000_000
	}
	test("a fractional manual value (kbit option) converts correctly") {
		MaxBitrateResolver.resolveBps("0.72", null) shouldBe 720_000
	}
	test("an invalid value falls back to detection like auto") {
		MaxBitrateResolver.resolveBps("garbage", 1_000_000) shouldBe 1_000_000
	}
})
