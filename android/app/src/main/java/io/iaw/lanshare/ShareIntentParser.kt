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
        val displayName = queryDisplayName(resolver, uri) ?: uri.lastPathSegment ?: "shared-file"
        val mimeType = resolver.getType(uri)
        return SharedItem(uri = uri, displayName = displayName, mimeType = mimeType)
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        val cursor: Cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?: return null
        cursor.use {
            if (!it.moveToFirst()) {
                return null
            }
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) {
                return null
            }
            return it.getString(index)
        }
    }
}
