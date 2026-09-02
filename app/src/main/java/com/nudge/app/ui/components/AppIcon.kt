package com.nudge.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object IconCache {
    private const val CACHE_SIZE = 120
    private val cache = object : LruCache<String, ImageBitmap>(CACHE_SIZE) {}

    fun get(packageName: String): ImageBitmap? = cache.get(packageName)

    fun loadAndRasterizeIcon(context: Context, packageName: String): ImageBitmap? {
        val cached = cache.get(packageName)
        if (cached != null) return cached

        val pm = context.packageManager
        val drawable = try {
            pm.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        } ?: return null

        val imageBitmap = rasterizeDrawable(drawable)?.asImageBitmap()
        if (imageBitmap != null) {
            cache.put(packageName, imageBitmap)
        }
        return imageBitmap
    }

    private fun rasterizeDrawable(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
        val targetWidth = width.coerceIn(48, 192)
        val targetHeight = height.coerceIn(48, 192)

        return try {
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, targetWidth, targetHeight)
            drawable.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
fun AppIcon(
    packageName: String,
    appName: String = "",
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shape: Shape = RoundedCornerShape(10.dp)
) {
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf(IconCache.get(packageName)) }

    LaunchedEffect(packageName) {
        if (bitmap == null) {
            val loaded = withContext(Dispatchers.IO) {
                IconCache.loadAndRasterizeIcon(context, packageName)
            }
            bitmap = loaded
        }
    }

    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap,
            contentDescription = appName.ifEmpty { packageName },
            modifier = modifier
                .size(size)
                .clip(shape)
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape),
            contentAlignment = Alignment.Center
        ) {
            val initial = appName.firstOrNull()?.uppercase()
                ?: packageName.substringAfterLast('.').firstOrNull()?.uppercase()
                ?: "?"
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
