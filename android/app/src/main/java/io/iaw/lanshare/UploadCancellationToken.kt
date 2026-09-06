package io.iaw.lanshare

import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean

internal class UploadCancelledException : IOException("Upload cancelled")

internal class UploadCancellationToken {
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
        resources.forEach { runCatching { it.close() } }
        runCatching { activeConnection?.disconnect() }
    }

    fun throwIfCancelled() {
        if (isCancelled) {
            throw UploadCancelledException()
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
            throw UploadCancelledException()
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
            throw UploadCancelledException()
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
