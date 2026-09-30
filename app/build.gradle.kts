import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing credentials (Play Store upload key) live in local.properties,
// alongside sdk.dir - never committed. See DEVELOPING.md's Releasing section.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.terraeclectic.deathfm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.terraeclectic.deathfm"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.4.6"

        // The wishlist (like button) talks to a private SpaceStation server and is
        // compiled in only when local.properties supplies its URL and Cloudflare
        // Access token - i.e. only in the maintainer's own builds. CI and the public
        // release builds have no such file, so WISHLIST_ENABLED is false and these
        // fields are empty: nothing private ends up in a released APK.
        val spaceStationUrl = localProperties.getProperty("SPACESTATION_URL", "")
        val spaceStationClientId = localProperties.getProperty("SPACESTATION_CF_ACCESS_CLIENT_ID", "")
        val spaceStationClientSecret = localProperties.getProperty("SPACESTATION_CF_ACCESS_CLIENT_SECRET", "")
        val wishlistEnabled = spaceStationUrl.isNotBlank() && spaceStationClientId.isNotBlank() && spaceStationClientSecret.isNotBlank()
        buildConfigField("boolean", "WISHLIST_ENABLED", wishlistEnabled.toString())
        buildConfigField("String", "SPACESTATION_URL", "\"${if (wishlistEnabled) spaceStationUrl else ""}\"")
        buildConfigField("String", "SPACESTATION_CF_ACCESS_CLIENT_ID", "\"${if (wishlistEnabled) spaceStationClientId else ""}\"")
        buildConfigField("String", "SPACESTATION_CF_ACCESS_CLIENT_SECRET", "\"${if (wishlistEnabled) spaceStationClientSecret else ""}\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = localProperties.getProperty("DEATHFM_KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = localProperties.getProperty("DEATHFM_KEYSTORE_PASSWORD")
                keyAlias = localProperties.getProperty("DEATHFM_KEY_ALIAS")
                keyPassword = localProperties.getProperty("DEATHFM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            ndk {
                // Play Console flags AABs with unstripped native libs (e.g. Compose's
                // libandroidx.graphics.path.so) unless a matching symbols file is
                // uploaded alongside. FULL bundles one automatically at
                // app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip.
                debugSymbolLevel = "FULL"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose - minimalist single-screen UI (station name, now playing, play/stop).
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Media3: ExoPlayer for streaming + MediaLibraryService, which is what
    // both the lock-screen/notification controls AND Android Auto are driven
    // by - see playback/PlaybackService.kt.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

    // Chromecast: media3-cast's CastPlayer becomes the MediaSession's active
    // player for as long as a cast session is connected (PlaybackService),
    // and the classic MediaRouteButton (via mediarouter + CastButtonFactory)
    // gives the phone/tablet UI its "Cast to" icon (PlayerScreen). Not
    // surfaced to Android Auto - the head unit isn't what's doing the casting.
    implementation("androidx.media3:media3-cast:1.4.1")
    // Explicit, current versions rather than whatever media3-cast/mediarouter
    // pull in transitively - confirmed live that an older framework/mediarouter
    // pairing (21.5.0/1.7.0) produced a real, intermittent bug: GMS's own
    // CastMediaRouteProvider logged "Published 1 routes" for a real Chromecast
    // on the network, but our app's own MediaRouter randomly logged "Ignoring
    // invalid provider descriptor: null" for the same event instead of
    // surfacing the route, so the picker sometimes searched forever and found
    // nothing despite a route genuinely being published moments earlier.
    implementation("com.google.android.gms:play-services-cast-framework:22.3.1")
    implementation("androidx.mediarouter:mediarouter:1.8.1")

    // For NowPlayingRepository's polling of death.fm's now-playing endpoint,
    // and LastFmClient's calls to the Audioscrobbler API.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // NowPlayingRepository/LastFmScrobbler run on coroutine scopes inside a
    // plain Service, not an Activity/ViewModel - lifecycle-runtime-ktx alone
    // doesn't pull this in transitively there.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Loads now-playing album art (NowPlayingMetadata.coverUrl) into PlayerScreen.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Parses the Queue/Played HTML fragments out of the player page's
    // get_db_info endpoint - see queueplayed/QueuePlayedRepository.kt.
    implementation("org.jsoup:jsoup:1.18.1")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub in JVM unit tests; the wishlist queue persists as JSON.
    testImplementation("org.json:json:20240303")
}
