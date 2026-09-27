package com.socialnetwork.app.data.model

/** Result returned by an authenticated REST or storage request. */
class HttpResult(
    val code: Int,
    val body: String,
    val retryAfter: String? = null
)
