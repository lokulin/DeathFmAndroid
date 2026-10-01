package com.terraeclectic.deathfm

import android.app.Application
import com.terraeclectic.deathfm.settings.SettingsStore
import com.terraeclectic.deathfm.wishlist.SharedPrefsWishlistStorage
import com.terraeclectic.deathfm.wishlist.RemoteWishlistApi
import com.terraeclectic.deathfm.wishlist.WishlistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DeathFmApp : Application() {
    lateinit var settings: SettingsStore
        private set

    /**
     * The like button's state and delivery queue - null in every build that
     * doesn't carry the private wishlist-server credentials (see
     * `BuildConfig.WISHLIST_ENABLED`), which is how the feature stays out of
     * the public releases.
     */
    var wishlist: WishlistRepository? = null
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)

        if (BuildConfig.WISHLIST_ENABLED) {
            val repository = WishlistRepository(
                storage = SharedPrefsWishlistStorage(this),
                sender = RemoteWishlistApi(
                    baseUrl = BuildConfig.WISHLIST_URL,
                    clientId = BuildConfig.WISHLIST_CF_ACCESS_CLIENT_ID,
                    clientSecret = BuildConfig.WISHLIST_CF_ACCESS_CLIENT_SECRET,
                ),
            )
            wishlist = repository
            // Anything liked while offline last time goes out now.
            appScope.launch { repository.flush() }
        }
    }
}
