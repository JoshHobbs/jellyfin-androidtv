package org.jellyfin.androidtv.util.profile

import kotlin.math.roundToInt

/**
 * Maps the stored Max Bitrate preference value to a bitrate ceiling in bits/sec.
 *
 * The [AUTO] sentinel (and any unparseable value) means "use the auto-detected bandwidth",
 * falling back to [FALLBACK_BPS] when no measurement exists yet. A numeric value is the
 * manual cap, expressed in megabits.
 */
object MaxBitrateResolver {
	const val AUTO = "auto"

	/** Conservative ceiling used for Auto before/without a measurement. Never "unlimited". */
	const val FALLBACK_BPS = 8_000_000

	fun resolveBps(prefValue: String, detectedBps: Int?): Int {
		val mbit = prefValue.toFloatOrNull()
		// "0" was a legacy value that broke playback; treating <0.01 (and unparseable) as auto
		// preserves that guard while routing those cases to detection.
		return if (prefValue == AUTO || mbit == null || mbit < 0.01f) {
			detectedBps ?: FALLBACK_BPS
		} else {
			(mbit * 1_000_000f).roundToInt()
		}
	}
}
