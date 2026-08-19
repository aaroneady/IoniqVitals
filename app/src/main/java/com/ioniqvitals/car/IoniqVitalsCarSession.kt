package com.ioniqvitals.car

import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.media.MediaPlaybackManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ioniqvitals.obd.ObdDataRepository
import com.ioniqvitals.ui.CarIconRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Hosts [VehicleStatusScreen] (the Car App template grid).
 *
 * Registering the MediaSession via [MediaPlaybackManager] is what makes Android Auto treat this
 * category.MEDIA Car App as a *templated media app* and actually open the template — without it,
 * AA falls back to the plain MediaBrowserService and shows the (empty) media browse instead. The
 * session also publishes the dashboard as album art for the now-playing surface.
 */
@ExperimentalCarApi
class IoniqVitalsCarSession : Session() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            @ExperimentalCarApi
            override fun onCreate(owner: LifecycleOwner) {
                val mediaSession = MediaSessionManager.getSession(carContext)
                carContext.getCarService(MediaPlaybackManager::class.java)
                    .registerMediaPlaybackToken(mediaSession.sessionToken)
                startDataObservation()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
                // The MediaSession is shared with the service; don't release it here.
            }
        })
    }

    override fun onCreateScreen(intent: Intent) = VehicleStatusScreen(carContext)

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        super.onCarConfigurationChanged(newConfiguration)
        carContext.getCarService(ScreenManager::class.java).top.invalidate()
    }

    private fun startDataObservation() {
        val iconRenderer = CarIconRenderer(carContext)
        scope.launch {
            ObdDataRepository.dataFlow.collectLatest { data ->
                val art = iconRenderer.createDashboardBitmap(
                    data.socDisplayPercent,
                    data.sohPercent,
                    data.coolantTempCelsius,
                    data.auxSocPercent,
                    data.headlightsOn,
                    data.brakeLightsOn,
                )
                MediaSessionManager.updateMetadata("", "", art)
            }
        }
    }
}
