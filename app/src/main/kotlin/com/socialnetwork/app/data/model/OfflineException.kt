package com.socialnetwork.app.data.model

/** Signals that a request cannot be completed while offline. */
class OfflineException(message: String) : Exception(message)
