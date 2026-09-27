package com.socialnetwork.app.core

/** Stable application constants shared by features. */
object AppConstants {
    const val PREFS = "social_network_session"
    const val ACCESS_TOKEN = "access_token"
    const val REFRESH_TOKEN = "refresh_token"
    const val CURRENT_USER_ID = "current_user_id"
    const val KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "social_network_session_key_v1"

    const val PICK_MEDIA = 4101
    const val CAMERA_CAPTURE = 4102

    const val MAX_MEDIA_BYTES = 50L * 1024L * 1024L
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    const val MAX_REQUEST_BODY_BYTES = 512 * 1024
    const val MAX_IMAGE_PREVIEW_BYTES = 8 * 1024 * 1024
    const val FEED_PAGE_SIZE = 20
    const val VERIFIED_USER_CACHE_MS = 60_000L
    const val TRANSIENT_RETRY_COUNT = 2
    const val TRANSIENT_RETRY_BASE_MS = 500L
    const val TRANSIENT_RETRY_MAX_MS = 5_000L

    const val NETWORK_CONNECT_TIMEOUT_MS = 15_000
    const val NETWORK_READ_TIMEOUT_MS = 30_000
}
