plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.terraeclectic.deathfm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.terraeclectic.deathfm"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    // Media3: ExoPlayer for streaming + MediaLibraryService, which is what
    // both the lock-screen/notification controls AND Android Auto are driven
    // by - see playback/PlaybackService.kt.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

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
}
