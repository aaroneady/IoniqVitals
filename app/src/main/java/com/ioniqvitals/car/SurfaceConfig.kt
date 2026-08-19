package com.ioniqvitals.car

/**
 * Compile-time selection of the Android Auto surface — a "templated media app" (see
 * https://developer.android.com/training/cars/apps/media).
 *
 * When [USE_TEMPLATE_SURFACE] is true, the [IoniqVitalsCarAppService] (category MEDIA) renders the Car
 * App template ([VehicleStatusScreen]) on hosts that support the Car App media-template host
 * (newer cars / AAOS). Hosts that don't — like the 2022 DHU — fall back to
 * [IoniqVitalsMediaBrowserService], so the media browser is kept enabled and its album-art dashboard
 * is always rendered as the fallback. (That fallback is why the DHU shows the media card, not the
 * GridTemplate; the template needs a newer host.)
 *
 * Set false for a media-only app (template service disabled); the media browser/album art is
 * unaffected either way.
 */
object SurfaceConfig {
    const val USE_TEMPLATE_SURFACE = false
}
