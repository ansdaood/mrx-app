package com.socialnetwork.app.sync

import com.socialnetwork.app.data.local.OfflineStore
import com.socialnetwork.app.data.model.HttpResult

/**
 * Kotlin-owned offline synchronization coordinator.
 * The Android Activity supplies the account/session and HTTP operations through callbacks.
 */
class OfflineSyncCoordinator(
    private val store: OfflineStore,
    private val maxAttempts: Int = 8,
    private val isOnline: () -> Boolean,
    private val hasSession: () -> Boolean,
    private val readSecret: (String) -> String,
    private val refreshSession: (String) -> Boolean,
    private val request: (String, String, String?, String) -> HttpResult,
    private val syncMediaOperation: (OfflineStore.PendingOperation) -> Boolean,
    private val pendingOperationBelongsToUser: (OfflineStore.PendingOperation, String) -> Boolean,
    private val postStatus: (String) -> Unit,
    private val onFinished: () -> Unit
) {
    @Volatile private var running = false

    fun isRunning(): Boolean = running

    fun runOnce() {
        if (!isOnline() || running || !hasSession()) return
        running = true
        try {
            val owner = readSecret("current_user_id")
            if (owner.isEmpty()) return

            var operations = store.pendingOperations(50, owner)
            for (legacy in store.unboundPendingOperations(20)) {
                if (pendingOperationBelongsToUser(legacy, owner)) {
                    store.bindPendingToUser(legacy.id, owner)
                }
            }
            operations = store.pendingOperations(50, owner)
            val refresh = readSecret("refresh_token")

            for (operation in operations) {
                if (!isOnline()) break
                if (store.pendingAttempts(operation.id) >= maxAttempts) continue
                try {
                    if (operation.url == "mrx://offline/post" || operation.url == "mrx://offline/story") {
                        if (syncMediaOperation(operation)) store.deletePending(operation.id) else break
                        continue
                    }

                    var token = readSecret("access_token")
                    var result = request(operation.method, operation.url, operation.body, token)
                    if (result.code == 401 && refresh.isNotEmpty() && refreshSession(refresh)) {
                        token = readSecret("access_token")
                        result = request(operation.method, operation.url, operation.body, token)
                    }

                    when {
                        result.code in 200..299 -> store.deletePending(operation.id)
                        result.code == 401 -> break
                        result.code in 400..499 && result.code != 408 && result.code != 429 ->
                            store.deletePending(operation.id)
                    }
                } catch (_: Exception) {
                    val attempts = store.incrementPendingAttempts(operation.id)
                    if (attempts >= maxAttempts) {
                        postStatus("تعذر مزامنة مهمة Offline بعد عدة محاولات؛ ستظل محفوظة محليًا ولن تتكرر بلا حد.")
                    }
                    break
                }
            }
        } finally {
            running = false
            onFinished()
        }
    }

    data class HttpResult(val code: Int)
}
