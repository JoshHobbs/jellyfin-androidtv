package org.jellyfin.androidtv.util.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.repository.SessionRepository
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps [BandwidthEstimateStore] fresh: once a server session exists, probes the link while it is
 * **idle** and re-probes every [REFRESH_INTERVAL_MS]. A probe taken during active playback competes
 * with the transcode stream and under-reads the link badly, so [isPlaybackActive] gates it — while
 * playback is active we just wait [PLAYBACK_RECHECK_MS] and look again, so the next real measurement
 * lands as soon as playback pauses or ends. [monitoringEnabled] is false outside foreground Auto
 * mode, and collectLatest cancels any active probe as soon as that changes.
 */
class BandwidthMonitor(
	private val sessionRepository: SessionRepository,
	private val detector: BandwidthDetector,
	private val store: BandwidthEstimateStore,
	private val monitoringEnabled: Flow<Boolean>,
	private val isPlaybackActive: () -> Boolean,
	private val clock: () -> Long,
) {
	private val started = AtomicBoolean(false)

	fun start(scope: CoroutineScope) {
		if (!started.compareAndSet(false, true)) return
		scope.launch {
			monitoringEnabled.distinctUntilChanged().collectLatest enabledCollector@{ enabled ->
				if (!enabled) return@enabledCollector
				sessionRepository.currentSession.collectLatest sessionCollector@{ session ->
					if (session == null) return@sessionCollector
					while (isActive) {
						if (isPlaybackActive()) {
							Timber.d("Bandwidth probe skipped: playback session active")
							delay(PLAYBACK_RECHECK_MS)
							continue
						}
						val probeScope = store.snapshotScope()
						val bps = if (probeScope == null) null else detectWhileIdle()
						if (!isPlaybackActive()) {
							when {
								bps == null -> Timber.w("Bandwidth probe failed; keeping previous estimate")
								probeScope != null && store.updateIfScopeMatches(bps, clock(), probeScope) ->
									Timber.i("Bandwidth probe: %d bps", bps)
								else -> Timber.d("Bandwidth probe discarded: server or network changed")
							}
							// Probe fast until the store can outvote a single bad reading, then settle down.
							// The probe is noisy, and the ceiling is a median: drawn from one or two samples
							// that median is meaningless (with two, the conservative tie-break is just the
							// minimum), so one junk reading would peg the ceiling far too low for the whole
							// window. Warming up quickly costs a handful of small downloads, once.
							val cold = store.sampleCount(clock()) < WARMUP_SAMPLES
							delay(if (cold) WARMUP_INTERVAL_MS else REFRESH_INTERVAL_MS)
						}
					}
				}
			}
		}
	}

	private suspend fun detectWhileIdle(): Int? = coroutineScope {
		val detection = async { detector.detect() }
		val playbackWatcher = launch {
			while (isActive) {
				delay(PLAYBACK_POLL_INTERVAL_MS)
				if (isPlaybackActive()) {
					Timber.d("Bandwidth probe cancelled: playback session started")
					detection.cancel()
					return@launch
				}
			}
		}
		try {
			val result = detection.await()
			if (isPlaybackActive()) null else result
		} catch (error: CancellationException) {
			if (isPlaybackActive()) null else throw error
		} finally {
			playbackWatcher.cancelAndJoin()
		}
	}

	companion object {
		const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L
		const val PLAYBACK_RECHECK_MS = 30 * 1000L
		const val PLAYBACK_POLL_INTERVAL_MS = 250L
		const val WARMUP_INTERVAL_MS = 45 * 1000L
		const val WARMUP_SAMPLES = 5
	}
}
