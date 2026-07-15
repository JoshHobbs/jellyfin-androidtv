package org.jellyfin.androidtv

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.startup.AppInitializer
import androidx.startup.Initializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.di.KoinInitializer
import org.jellyfin.androidtv.util.profile.BandwidthMonitor

@Suppress("unused")
class SessionInitializer : Initializer<Unit> {
	override fun create(context: Context) {
		val koin = AppInitializer.getInstance(context)
			.initializeComponent(KoinInitializer::class.java)
			.koin

		// Restore system session
		ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
			koin.get<SessionRepository>().restoreSession(destroyOnly = false)
		}

		// Keep the auto-detected bandwidth estimate fresh while foreground Auto mode is active.
		koin.get<BandwidthMonitor>().start(ProcessLifecycleOwner.get().lifecycleScope)
	}

	override fun dependencies() = listOf(KoinInitializer::class.java)
}
