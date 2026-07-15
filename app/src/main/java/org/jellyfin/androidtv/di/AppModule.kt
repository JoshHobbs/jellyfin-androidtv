package org.jellyfin.androidtv.di

import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.NetworkFetcher
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.serviceLoaderEnabled
import coil3.svg.SvgDecoder
import coil3.util.Logger
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.auth.repository.UserRepositoryImpl
import org.jellyfin.androidtv.data.eventhandling.SocketHandler
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.CustomMessageRepositoryImpl
import org.jellyfin.androidtv.data.repository.ExternalAppRepository
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.data.repository.ItemMutationRepositoryImpl
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepositoryImpl
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepositoryImpl
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.integration.dream.DreamViewModel
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.navigation.NavigationRepositoryImpl
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer
import org.jellyfin.androidtv.ui.playback.external.DefaultExternalPlayerApi
import org.jellyfin.androidtv.ui.playback.external.ExternalPlayerApi
import org.jellyfin.androidtv.ui.playback.external.MpvExternalPlayerApi
import org.jellyfin.androidtv.ui.playback.external.MxExternalPlayerApi
import org.jellyfin.androidtv.ui.playback.external.VimuExternalPlayerApi
import org.jellyfin.androidtv.ui.playback.external.VlcExternalPlayerApi
import org.jellyfin.androidtv.ui.playback.nextup.NextUpViewModel
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentRepository
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentRepositoryImpl
import org.jellyfin.androidtv.ui.playback.stillwatching.StillWatchingViewModel
import org.jellyfin.androidtv.ui.player.photo.PhotoPlayerViewModel
import org.jellyfin.androidtv.ui.search.SearchFragmentDelegate
import org.jellyfin.androidtv.ui.search.SearchRepository
import org.jellyfin.androidtv.ui.search.SearchRepositoryImpl
import org.jellyfin.androidtv.ui.search.SearchViewModel
import org.jellyfin.androidtv.ui.settings.compat.SettingsViewModel
import org.jellyfin.androidtv.ui.startup.ServerAddViewModel
import org.jellyfin.androidtv.ui.startup.StartupViewModel
import org.jellyfin.androidtv.ui.startup.UserLoginViewModel
import org.jellyfin.androidtv.util.AndroidVersion
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.androidtv.util.MarkdownRenderer
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.apiclient.ReportingHelper
import org.jellyfin.androidtv.util.coil.CoilTimberLogger
import org.jellyfin.androidtv.util.coil.createCoilConnectivityChecker
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.util.profile.PassiveBandwidthSampler
import org.jellyfin.androidtv.util.profile.BandwidthDetector
import org.jellyfin.androidtv.util.profile.BandwidthEstimateScope
import org.jellyfin.androidtv.util.profile.BandwidthEstimateStore
import org.jellyfin.androidtv.util.profile.BandwidthMonitor
import org.jellyfin.androidtv.util.profile.MaxBitrateResolver
import org.jellyfin.androidtv.util.sdk.SdkPlaybackHelper
import org.jellyfin.sdk.android.androidDevice
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.HttpClientOptions
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resumeWithException
import org.jellyfin.sdk.Jellyfin as JellyfinSdk

val defaultDeviceInfo = named("defaultDeviceInfo")

/** Monotonic millisecond clock shared by the bandwidth-estimation components. */
private val elapsedRealtimeClock: () -> Long = { android.os.SystemClock.elapsedRealtime() }

/** Execute an OkHttp probe without losing coroutine cancellation at the blocking network boundary. */
private suspend fun Call.awaitBodyStream(): InputStream = suspendCancellableCoroutine { continuation ->
	continuation.invokeOnCancellation { cancel() }
	enqueue(object : Callback {
		override fun onFailure(call: Call, e: IOException) {
			if (continuation.isActive) continuation.resumeWithException(e)
		}

		override fun onResponse(call: Call, response: Response) {
			val body = response.body
			if (!response.isSuccessful || body == null) {
				val error = IllegalStateException("Bitrate test failed: HTTP ${response.code}")
				response.close()
				if (continuation.isActive) continuation.resumeWithException(error)
				return
			}

			if (!continuation.isActive) {
				response.close()
				return
			}

			continuation.resume(body.byteStream()) { _, stream, _ -> stream.close() }
		}
	})
}

val appModule = module {
	// SDK
	single(defaultDeviceInfo) { androidDevice(get()) }
	single { OkHttpFactory() }
	single { HttpClientOptions() }
	single {
		createJellyfin {
			context = androidContext()

			// Add client info
			val clientName = buildString {
				append("Jellyfin for Android TV")
				if (BuildConfig.DEBUG) append(" (debug)")
			}
			clientInfo = ClientInfo(clientName, BuildConfig.VERSION_NAME)
			deviceInfo = get(defaultDeviceInfo)

			// Change server version
			minimumServerVersion = ServerRepository.minimumServerVersion

			// Use our own shared factory instance
			apiClientFactory = get<OkHttpFactory>()
			socketConnectionFactory = get<OkHttpFactory>()
		}
	}

	single {
		// Create an empty API instance, the actual values are set by the SessionRepository
		get<JellyfinSdk>().createApi(httpClientOptions = get<HttpClientOptions>())
	}

	single { SocketHandler(get(), get(), get(), get(), get(), get(), get(), get(), get(), ProcessLifecycleOwner.get().lifecycle) }

	// Coil (images)
	single {
		val okHttpFactory = get<OkHttpFactory>()
		val httpClientOptions = get<HttpClientOptions>()

		@OptIn(ExperimentalCoilApi::class)
		OkHttpNetworkFetcherFactory(
			callFactory = { okHttpFactory.createClient(httpClientOptions) },
			connectivityChecker = ::createCoilConnectivityChecker,
		)
	}

	single {
		ImageLoader.Builder(androidContext()).apply {
			serviceLoaderEnabled(false)
			logger(CoilTimberLogger(if (BuildConfig.DEBUG) Logger.Level.Warn else Logger.Level.Error))

			components {
				add(get<NetworkFetcher.Factory>())

				if (AndroidVersion.isAtLeastP) add(AnimatedImageDecoder.Factory())
				else add(GifDecoder.Factory())
				add(SvgDecoder.Factory())
			}
		}.build()
	}

	// Bandwidth detection for Auto Max Bitrate
	single {
		val sessionRepository = get<org.jellyfin.androidtv.auth.repository.SessionRepository>()
		val connectivityManager = androidContext().getSystemService(ConnectivityManager::class.java)
		BandwidthEstimateStore(currentScope = {
			sessionRepository.currentSession.value?.let { session ->
				BandwidthEstimateScope(session.serverId, connectivityManager.activeNetwork?.networkHandle)
			}
		})
	}
	single { PassiveBandwidthSampler(get(), clock = elapsedRealtimeClock) }
	// apiClient is the shared mutable singleton; SessionRepository patches its baseUrl/token in
	// place on each session change, so the captured reference always reflects the active session.
	single {
		val apiClient = get<ApiClient>()
		val okHttpClient = get<OkHttpFactory>().createClient(get<HttpClientOptions>())
		BandwidthDetector(
			// Stream the payload rather than using the SDK's typed call, which buffers the whole body
			// and hands back a ByteArray. A buffered body can only be timed end-to-end, from the first
			// byte — and that measurement is dominated by TTFB and TCP slow-start on a weak link, which
			// is what made the old fixed-size probe under-read by 4-8x. The detector needs to watch the
			// bytes arrive so it can throw the ramp away and time only the steady state.
			openStream = { size ->
				// The SDK builds the bitrate-test URL with the access token embedded (same builder
				// family the video/subtitle stream URLs use), so we don't hand-assemble base URL,
				// path, or api_key here.
				val request = Request.Builder()
					.url(apiClient.mediaInfoApi.getBitrateTestBytesUrl(size))
					.build()
				okHttpClient.newCall(request).awaitBodyStream()
			},
			clock = elapsedRealtimeClock,
		)
	}
	single {
		val playbackControllerContainer = get<PlaybackControllerContainer>()
		val userPreferences = get<UserPreferences>()
		val foreground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
			.map { it.isAtLeast(Lifecycle.State.STARTED) }
		val autoBitrate = userPreferences.maxBitrateFlow()
			.map { it == MaxBitrateResolver.AUTO }
		BandwidthMonitor(
			get(), get(), get(),
			monitoringEnabled = combine(foreground, autoBitrate) { isForeground, isAuto ->
				isForeground && isAuto
			}.distinctUntilChanged(),
			// Skip probes while a playback session is live — they would compete with the transcode
			// stream and under-read the link. This must NOT use isPlaying(): that is false while the
			// player is buffering, seeking or paused, and all three still move data (a paused player
			// keeps filling its deep buffer), so probes leaked into exactly the moments the link was
			// busiest. Only a torn-down player is quiet enough to measure.
			isPlaybackActive = { playbackControllerContainer.playbackController?.isPlaybackSessionActive() == true },
			clock = elapsedRealtimeClock,
		)
	}

	// Non API related
	single { DataRefreshService() }
	single { PlaybackControllerContainer() }
	single { InteractionTrackerViewModel(get(), get()) }

	single<UserRepository> { UserRepositoryImpl() }
	single<UserViewsRepository> { UserViewsRepositoryImpl(get()) }
	single<NotificationsRepository> { NotificationsRepositoryImpl(get(), get()) }
	single<ItemMutationRepository> { ItemMutationRepositoryImpl(get(), get()) }
	single<CustomMessageRepository> { CustomMessageRepositoryImpl() }
	single<NavigationRepository> { NavigationRepositoryImpl(Destinations.home) }
	single<SearchRepository> { SearchRepositoryImpl(get()) }
	single<MediaSegmentRepository> { MediaSegmentRepositoryImpl(get(), get()) }
	single<ExternalAppRepository> { ExternalAppRepository(get(), getAll(), get<DefaultExternalPlayerApi>()) }

	// External player APIs
	single { VlcExternalPlayerApi() } bind ExternalPlayerApi::class
	single { MxExternalPlayerApi() } bind ExternalPlayerApi::class
	single { MpvExternalPlayerApi() } bind ExternalPlayerApi::class
	single { VimuExternalPlayerApi() } bind ExternalPlayerApi::class
	single { DefaultExternalPlayerApi() }

	viewModel { StartupViewModel(get(), get(), get(), get()) }
	viewModel { UserLoginViewModel(get(), get(), get(), get(defaultDeviceInfo)) }
	viewModel { ServerAddViewModel(get()) }
	viewModel { NextUpViewModel(get(), get(), get()) }
	viewModel { StillWatchingViewModel(get(), get(), get(), get()) }
	viewModel { PhotoPlayerViewModel(get()) }
	viewModel { SearchViewModel(get()) }
	viewModel { DreamViewModel(get(), get(), get(), get(), get()) }
	viewModel { SettingsViewModel() }

	single { BackgroundService(get(), get(), get(), get(), get()) }

	single { MarkdownRenderer(get()) }
	single { ItemLauncher() }
	single { KeyProcessor() }
	single { ReportingHelper(get(), get()) }
	single<PlaybackHelper> { SdkPlaybackHelper(get(), get(), get(), get()) }

	factory { (context: Context) -> SearchFragmentDelegate(context, get(), get()) }
}
