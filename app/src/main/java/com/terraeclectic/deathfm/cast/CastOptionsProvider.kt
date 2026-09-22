package com.terraeclectic.deathfm.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Required hook-up point the Cast SDK looks for by class name (see the
 * OPTIONS_PROVIDER_CLASS_NAME meta-data in AndroidManifest.xml). Uses our own
 * registered Styled Media Receiver (Cast SDK Developer Console, app name
 * "Death.FM") instead of the shared default receiver, so the TV shows our
 * branding/skin instead of "Default Media Receiver" - see cast/README or the
 * deathfm-cast Pages skin for the CSS.
 */
class CastOptionsProvider : OptionsProvider {
    // While the console app is "Unpublished", casting only works on
    // Chromecast devices added as test devices for this app in the console
    // (by serial number) - anyone else's TV won't be able to launch it.
    private val receiverApplicationId = "9FD9AE52"

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(receiverApplicationId)
            // false, not true: the whole point of casting is that the TV
            // keeps playing independently of the phone/app - true tells the
            // Cast SDK to explicitly stop the receiver whenever our session
            // ends for any reason, which is exactly what was happening when
            // Android tore down our background service after the screen
            // locked (confirmed live: cast playback stopped on screen lock).
            .setStopReceiverApplicationWhenEndingSession(false)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
