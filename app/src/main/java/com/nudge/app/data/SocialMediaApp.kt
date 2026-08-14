package com.nudge.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

enum class SocialMediaApp(
    val appName: String,
    val packageNames: Set<String>
) {
    INSTAGRAM("Instagram", setOf("com.instagram.android")),
    FACEBOOK("Facebook", setOf("com.facebook.katana", "com.facebook.lite")),
    TIKTOK("TikTok", setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")),
    X_TWITTER("X", setOf("com.twitter.android")),
    YOUTUBE("YouTube", setOf("com.google.android.youtube")),
    SNAPCHAT("Snapchat", setOf("com.snapchat.android")),
    REDDIT("Reddit", setOf("com.reddit.frontpage")),
    WHATSAPP("WhatsApp", setOf("com.whatsapp", "com.whatsapp.w4b")),
    TELEGRAM("Telegram", setOf("org.telegram.messenger", "org.thunderdog.challegram")),
    THREADS("Threads", setOf("com.instagram.barcelona"));

    fun isInstalled(context: Context): Boolean {
        return packageNames.any { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    fun getIcon(context: Context): Drawable? {
        return packageNames.firstNotNullOfOrNull { pkg ->
            try {
                context.packageManager.getApplicationIcon(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    companion object {
        val ALL_PACKAGE_NAMES: Set<String> = entries.flatMap { it.packageNames }.toSet()

        fun findByPackageName(packageName: String): SocialMediaApp? {
            return entries.firstOrNull { it.packageNames.contains(packageName) }
        }
    }
}

