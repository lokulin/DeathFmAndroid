package com.terraeclectic.deathfm.lastfm

/**
 * This app's own registered Last.fm application identifiers (the same ones
 * the sibling DeathFmTray and SomaMetalTray desktop apps use) - this just
 * identifies "the application" to Last.fm's API and isn't a secret that
 * grants access to any user's account; each user still authenticates their
 * own separate Last.fm account via the in-app Connect flow.
 */
object LastFmCredentials {
    const val API_KEY = "53a48adcaf85d2da5e316b0cd4b2d53c"
    const val API_SECRET = "07f6d35faa283f3da90ba7ce79c81528"
}
