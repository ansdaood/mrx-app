package com.socialnetwork.app.features

import com.socialnetwork.app.core.FeatureFlags

/** Human-readable feature inventory used by diagnostics and future feature work. */
object FeatureRegistry {
    fun current(): Map<String, Boolean> = mapOf(
        "feed_pagination" to FeatureFlags.FEED_PAGINATION,
        "stories" to FeatureFlags.STORIES,
        "likes" to FeatureFlags.LIKES,
        "comments" to FeatureFlags.COMMENTS,
        "profiles" to FeatureFlags.PROFILES,
        "follows" to FeatureFlags.FOLLOWS,
        "notifications" to FeatureFlags.NOTIFICATIONS,
        "messages" to FeatureFlags.MESSAGES
    )
}
