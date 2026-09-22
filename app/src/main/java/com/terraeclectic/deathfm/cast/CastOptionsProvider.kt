package com.terraeclectic.deathfm.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Required hook-up point the Cast SDK looks for by class name (see the
 * OPTIONS_PROVIDER_CLASS_NAME meta-data in AndroidManifest.xml). Uses our
 * own registered Custom Receiver ("Death.FM Player", Cast SDK Developer
 * Console) instead of the shared default receiver, so the TV shows our
 * branding and a real live elapsed timer instead of "Default Media
 * Receiver" and a stuck "0:00" - see the
 * [lokulin/DeathFmCastReceiver](https://github.com/lokulin/DeathFmCastReceiver)
 * repo for the receiver's own source (deployed to
 * https://deathfm-cast.l6n.uk/). Superseded the earlier Styled Media
 * Receiver approach (still in that repo's styled/ folder for reference) -
 * its prebuilt live-mode UI had no progress indicator at all and made stop
 * unresumable when tried from the sender side via STREAM_TYPE_LIVE.
 */
class CastOptionsProvider : OptionsProvider {
    // While the console app is "Unpublished", casting only works on
    // Chromecast devices added under "Cast Receiver Devices" in the console
    // (by serial number) - anyone else's TV won't be able to launch it.
    // That device list is account-wide, not per-application (confirmed live:
    // a device added while testing the earlier Styled Media Receiver showed
    // up already authorized here too, no re-adding needed).
    private val receiverApplicationId = "0CD00C8F"

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
