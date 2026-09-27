package com.socialnetwork.app.auth

import android.util.Base64
import java.security.SecureRandom

/** Kotlin-owned helpers migrated from the legacy MainActivity implementation. */
object AuthUtils {
    fun randomRecoveryState(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    fun isRecoveryStateFresh(issuedAt: Long, now: Long, ttlMs: Long): Boolean =
        issuedAt > 0L && now - issuedAt in 0..ttlMs

    fun isSuccessfulHttp(code: Int): Boolean = code in 200..299
}
