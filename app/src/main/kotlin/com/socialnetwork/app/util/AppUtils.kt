package com.socialnetwork.app.util

import android.net.Uri
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** Pure/small helpers migrated from MainActivity.java during Kotlin migration. */
object AppUtils {
    private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private val UUID_REGEX = Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    fun configureSystemBars(activity: android.app.Activity) {
        val window = activity.window
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        } else if (android.os.Build.VERSION.SDK_INT >= 21) {
            window.statusBarColor = android.graphics.Color.WHITE
            window.navigationBarColor = android.graphics.Color.WHITE
        }
    }

    fun isValidConfiguredEndpoint(value: String?): Boolean = try {
        val uri = Uri.parse(value ?: return false)
        "https".equals(uri.scheme, ignoreCase = true) &&
            uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            !value.contains("YOUR_PROJECT") &&
            (uri.port == -1 || uri.port in 1..65535)
    } catch (_: Exception) { false }

    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))
    }

    fun isValidEmail(email: String?): Boolean =
        email?.let { it.length <= 254 && EMAIL_REGEX.matches(it) } == true

    fun normalizeUsernameSearch(value: String?): String {
        val s = value?.trim()?.take(32).orEmpty()
        return buildString(s.length) {
            for (ch in s) {
                if (ch == '*' || ch == '%' || ch == '_' || ch == ',' || ch == '(' || ch == ')' || ch == '\\') continue
                if (ch.isISOControl()) continue
                append(ch)
            }
        }
    }

    fun isUuid(value: String?): Boolean =
        value?.let(UUID_REGEX::matches) == true

    fun containsTraversal(path: String?): Boolean {
        if (path.isNullOrEmpty()) return true
        val normalized = path.replace('\\', '/')
        if (normalized.contains("%2e", true) || normalized.contains("%2f", true) || normalized.contains("%5c", true)) return true
        return normalized.split("/").any { it == "." || it == ".." }
    }

    fun isTrustedMediaUrl(value: String?, storageUrl: String?): Boolean = try {
        val uri = Uri.parse(value ?: return false)
        val base = Uri.parse(storageUrl ?: return false)
        val path = uri.path
        if (!"https".equals(uri.scheme, true) || uri.host == null || !uri.host.equals(base.host, true) ||
            uri.userInfo != null || uri.query != null || uri.fragment != null ||
            (if (uri.port == -1) 443 else uri.port) != (if (base.port == -1) 443 else base.port) ||
            path == null || !path.startsWith("/storage/v1/object/public/media/") || containsTraversal(path)) return false
        val decoded = Uri.decode(path)
        !containsTraversal(decoded)
    } catch (_: Exception) { false }

    fun mediaExtension(mime: String?): String? {
        if (mime == null) return null
        return when (mime.lowercase(Locale.US).split(';', limit = 2)[0].trim()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            else -> null
        }
    }

    fun errorMessage(body: String?): String = try {
        val o = JSONObject(body ?: "")
        val m = o.optString("msg").ifEmpty { o.optString("message") }.ifEmpty { o.optString("error_description") }
        if (m.isEmpty()) body.orEmpty() else m
    } catch (_: Exception) { if (body.isNullOrEmpty()) "خطأ غير معروف" else body }

    fun safeMessage(e: Exception?): String {
        if (e == null) return "خطأ غير معروف"
        return e.message?.takeIf { it.isNotEmpty() } ?: e.javaClass.simpleName
    }
}
