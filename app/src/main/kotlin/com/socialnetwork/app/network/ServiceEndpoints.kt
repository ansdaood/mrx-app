package com.socialnetwork.app.network

import android.net.Uri

/** Immutable service map with strict HTTPS-origin validation and conservative routing. */
class ServiceEndpoints(
    primary: String?,
    dataRead: String?,
    dataWrite: String?,
    auth: String?,
    storage: String?,
    key: String?
) {
    val primary: String = clean(primary)
    val dataRead: String = fallback(clean(dataRead), this.primary)
    val dataWrite: String = fallback(clean(dataWrite), this.primary)
    val auth: String = fallback(clean(auth), this.primary)
    val storage: String = fallback(clean(storage), this.primary)
    val key: String = key?.trim().orEmpty()
    val clients = ServiceClients(this.auth, this.dataRead, this.dataWrite, this.storage)

    fun serviceBase(service: String): String = when (service) {
        "auth" -> clients.auth.baseUrl()
        "data_read" -> clients.dataRead.baseUrl()
        "data_write" -> clients.dataWrite.baseUrl()
        "storage" -> clients.storage.baseUrl()
        else -> primary
    }

    fun configured(): Boolean =
        primary.isNotEmpty() && key.isNotEmpty() &&
            validOrigin(primary) && validOrigin(dataRead) && validOrigin(dataWrite) &&
            validOrigin(auth) && validOrigin(storage)

    fun trusts(endpoint: String?): Boolean {
        val value = endpoint?.trim().orEmpty()
        if (value.isEmpty()) return false
        return listOf(primary, dataRead, dataWrite, auth, storage)
            .filter(String::isNotEmpty)
            .any { sameOriginOrigin(it, value) }
    }

    fun isReadReplica(method: String?, url: String?): Boolean {
        if (!method.isReadOnlyHttpMethod()) return false
        val value = url?.trim().orEmpty()
        return value.isNotEmpty() && sameOriginOrigin(value, dataRead) && !sameOriginOrigin(value, primary)
    }

    fun fallbackWrite(url: String): String {
        val path = pathFromOrigin(url, dataRead) ?: return url
        return dataWrite + path
    }

    private fun validOrigin(endpoint: String): Boolean = isHttps(endpoint) && safeOrigin(endpoint)

    private fun isHttps(endpoint: String): Boolean = try {
        Uri.parse(endpoint).scheme.equals("https", ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    private fun safeOrigin(endpoint: String): Boolean = try {
        val u = Uri.parse(endpoint)
        u.host != null && u.userInfo == null && u.fragment == null && u.query == null &&
            (u.path.isNullOrEmpty() || u.path == "/") &&
            (u.port == -1 || u.port in 1..65535)
    } catch (_: Exception) {
        false
    }

    fun route(method: String?, url: String?): String? {
        val value = url ?: return null
        val path = pathFromTrustedService(value) ?: return value
        return when {
            path.startsWith("/auth/") -> auth + path
            path.startsWith("/storage/") -> storage + path
            path.startsWith("/rest/") -> {
                val read = method.isReadOnlyHttpMethod()
                (if (read) dataRead else dataWrite) + path
            }
            else -> value
        }
    }

    private fun String?.isReadOnlyHttpMethod(): Boolean =
        equals("GET", ignoreCase = true) || equals("HEAD", ignoreCase = true)

    private fun pathFromTrustedService(url: String): String? = try {
        val u = Uri.parse(url)
        if (!validRoutableUrl(u)) return null
        val trustedOrigins = listOf(primary, dataRead, dataWrite, auth, storage)
        if (!trustedOrigins.any { sameOriginOrigin(it, url) }) return null
        buildString {
            append(u.path ?: "")
            u.query?.let { append('?').append(it) }
        }
    } catch (_: Exception) {
        null
    }

    private fun pathFromOrigin(url: String, origin: String): String? = try {
        val u = Uri.parse(url)
        if (!sameOriginOrigin(url, origin) || !validRoutableUrl(u)) return null
        buildString {
            append(u.path ?: "")
            u.query?.let { append('?').append(it) }
        }
    } catch (_: Exception) {
        null
    }

    private fun validRoutableUrl(uri: Uri): Boolean =
        uri.scheme.equals("https", true) &&
            uri.host != null &&
            uri.userInfo == null &&
            uri.fragment == null &&
            (uri.port == -1 || uri.port in 1..65535)

    private fun sameOriginOrigin(first: String, second: String): Boolean = try {
        val a = Uri.parse(first)
        val b = Uri.parse(second)
        a.scheme.equals(b.scheme, true) &&
            a.host != null && a.host.equals(b.host, true) &&
            effectivePort(a) == effectivePort(b) &&
            a.userInfo == null && b.userInfo == null &&
            a.fragment == null && b.fragment == null &&
            a.query == null && b.query == null
    } catch (_: Exception) {
        false
    }

    private fun effectivePort(uri: Uri): Int = if (uri.port == -1) 443 else uri.port

    companion object {
        private fun clean(value: String?): String = value?.trim()?.trimEnd('/').orEmpty()
        private fun fallback(value: String, primary: String): String = if (value.isEmpty()) primary else value
    }
}
