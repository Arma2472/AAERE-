package com.example.betterauto.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AppCategory(val label: String) {
    ALL("Toutes"),
    NAVIGATION("Navigation"),
    MEDIA("Média"),
    COMMUNICATION("Appels & Msg"),
    OTHER("Utilitaires")
}

data class LaunchableApp(
    val packageName: String,
    val label: String,
    val category: AppCategory,
    val iconBitmap: Bitmap?
)

/**
 * Loads launchable apps from PackageManager cleanly across Android 10 (API 29) to Android 16 (API 36).
 */
class AppLauncherRepository(private val context: Context) {

    suspend fun loadLaunchableApps(): List<LaunchableApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        resolveList
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != context.packageName }
            .map { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName
                val label = resolveInfo.loadLabel(pm)?.toString() ?: pkg
                val icon = runCatching { drawableToBitmap(resolveInfo.loadIcon(pm)) }.getOrNull()
                LaunchableApp(
                    packageName = pkg,
                    label = label,
                    category = categorizePackage(pkg, label),
                    iconBitmap = icon
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun categorizePackage(pkg: String, label: String): AppCategory {
        val p = pkg.lowercase()
        val l = label.lowercase()
        return when {
            p.contains("maps") || p.contains("waze") || p.contains("osmand") ||
                p.contains("tomtom") || p.contains("sygic") || l.contains("gps") -> AppCategory.NAVIGATION
            p.contains("spotify") || p.contains("deezer") || p.contains("music") ||
                p.contains("vlc") || p.contains("podcast") || p.contains("tidal") ||
                p.contains("radio") || p.contains("audible") || l.contains("musique") -> AppCategory.MEDIA
            p.contains("dialer") || p.contains("phone") || p.contains("whatsapp") ||
                p.contains("telegram") || p.contains("messaging") || p.contains("signal") ||
                l.contains("téléphone") || l.contains("messages") -> AppCategory.COMMUNICATION
            else -> AppCategory.OTHER
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
        val bitmap = Bitmap.createBitmap(width.coerceAtMost(144), height.coerceAtMost(144), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
