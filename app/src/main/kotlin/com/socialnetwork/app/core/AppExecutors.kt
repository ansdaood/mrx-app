package com.socialnetwork.app.core

import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Shared bounded background execution policy. */
class AppExecutors {
    companion object {
        private const val IO_THREADS = 6
        private const val IO_QUEUE_CAPACITY = 256
    }

    private val ioExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        IO_THREADS,
        IO_THREADS,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(IO_QUEUE_CAPACITY),
        ThreadPoolExecutor.AbortPolicy()
    )

    fun io(): ExecutorService = ioExecutor

    fun shutdown() {
        ioExecutor.shutdownNow()
    }
}
