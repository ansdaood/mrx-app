package com.socialnetwork.app.network

/** Immutable endpoint clients. */
class ServiceClients(
    authUrl: String?,
    dataReadUrl: String?,
    dataWriteUrl: String?,
    storageUrl: String?
) {
    val auth = Client(authUrl)
    val dataRead = Client(dataReadUrl)
    val dataWrite = Client(dataWriteUrl)
    val storage = Client(storageUrl)

    class Client(baseUrl: String?) {
        val baseUrlValue: String = normalize(baseUrl)

        fun baseUrl(): String = baseUrlValue

        fun path(path: String?): String = when {
            path.isNullOrEmpty() -> baseUrlValue
            path.startsWith('/') -> baseUrlValue + path
            else -> "$baseUrlValue/$path"
        }

        fun auth(path: String?) = path("/auth/v1/${trimLeading(path)}")
        fun rest(path: String?) = path("/rest/v1/${trimLeading(path)}")
        fun storage(path: String?) = path("/storage/v1/${trimLeading(path)}")

        companion object {
            private fun trimLeading(value: String?): String {
                if (value == null) return ""
                var i = 0
                while (i < value.length && value[i] == '/') i++
                return value.substring(i)
            }

            private fun normalize(value: String?): String {
                return value?.trim()?.trimEnd('/').orEmpty()
            }
        }
    }
}
