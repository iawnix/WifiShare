package io.iaw.lanshare

import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executor

internal class TransferCancelledException : IOException("Transfer cancelled")

internal class TransferCancellationToken(
    private val cleanupExecutor: Executor = Executor { it.run() },
) {
    private val cancelled = AtomicBoolean(false)
    private val resourceLock = Any()
    private val closeables = mutableSetOf<Closeable>()
    private var connection: HttpURLConnection? = null

    val isCancelled: Boolean get() = cancelled.get()

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return
        }
        val resources: List<Closeable>
        val activeConnection: HttpURLConnection?
        synchronized(resourceLock) {
            resources = closeables.toList()
            closeables.clear()
            activeConnection = connection
            connection = null
        }
        // Disconnect/close can block in a network or document provider. Services supply
        // a separate executor so cancellation never blocks the Android main thread.
        cleanupExecutor.execute {
            runCatching { activeConnection?.disconnect() }
            resources.forEach { runCatching { it.close() } }
        }
    }

    fun throwIfCancelled() {
        if (isCancelled) {
            throw TransferCancelledException()
        }
    }

    fun attach(closeable: Closeable) {
        val closeImmediately = synchronized(resourceLock) {
            if (isCancelled) true else {
                closeables += closeable
                false
            }
        }
        if (closeImmediately) {
            runCatching { closeable.close() }
            throw TransferCancelledException()
        }
    }

    fun detach(closeable: Closeable) {
        synchronized(resourceLock) {
            closeables -= closeable
        }
    }

    fun attach(connection: HttpURLConnection) {
        val disconnectImmediately = synchronized(resourceLock) {
            if (isCancelled) true else {
                this.connection = connection
                false
            }
        }
        if (disconnectImmediately) {
            connection.disconnect()
            throw TransferCancelledException()
        }
    }

    fun detach(connection: HttpURLConnection) {
        synchronized(resourceLock) {
            if (this.connection === connection) {
                this.connection = null
            }
        }
    }
}
