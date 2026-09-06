package io.iaw.lanshare

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns

data class SharedItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
)

object ShareIntentParser {
    fun parse(context: Context, intent: Intent?): List<SharedItem> {
        if (intent == null) {
            return emptyList()
        }
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(singleUri(intent)).map { buildItem(context, it) }
            Intent.ACTION_SEND_MULTIPLE -> multipleUris(intent).map { buildItem(context, it) }
            else -> emptyList()
        }
    }

    fun fromUris(context: Context, uris: List<Uri>): List<SharedItem> {
        return uris.map { buildItem(context, it) }
    }

    private fun singleUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun multipleUris(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: arrayListOf()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf()
        }
    }

    private fun buildItem(context: Context, uri: Uri): SharedItem {
        val resolver = context.contentResolver
        val metadata = queryMetadata(resolver, uri)
        val displayName = metadata.displayName ?: uri.lastPathSegment ?: "shared-file"
        val mimeType = resolver.getType(uri)
        return SharedItem(
            uri = uri,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = metadata.sizeBytes,
        )
    }

    private fun queryMetadata(resolver: ContentResolver, uri: Uri): SharedItemMetadata {
        val cursor: Cursor = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        ) ?: return SharedItemMetadata()
        cursor.use {
            if (!it.moveToFirst()) {
                return SharedItemMetadata()
            }
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
            return SharedItemMetadata(
                displayName = nameIndex.takeIf { index -> index >= 0 && !it.isNull(index) }
                    ?.let(it::getString),
                sizeBytes = sizeIndex.takeIf { index -> index >= 0 && !it.isNull(index) }
                    ?.let(it::getLong)
                    ?.takeIf { size -> size >= 0L },
            )
        }
    }

    private data class SharedItemMetadata(
        val displayName: String? = null,
        val sizeBytes: Long? = null,
    )
}
