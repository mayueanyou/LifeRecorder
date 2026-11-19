package io.github.mayueanyou.lifetracker.ui

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App icons, read once each and kept: a timeline full of sessions asks for the same few apps again and again. */
object AppIcons {
    private val cache = LruCache<String, Any>(64)
    private const val NONE = "none"

    fun get(context: Context, pkg: String): ImageBitmap? {
        cache.get(pkg)?.let { return it as? ImageBitmap }
        val icon = runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(72, 72).asImageBitmap() }.getOrNull()
        cache.put(pkg, icon ?: NONE)
        return icon
    }
}

@Composable
fun appIcon(pkg: String): ImageBitmap? {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) { AppIcons.get(context, pkg) }
    }
    return icon
}
