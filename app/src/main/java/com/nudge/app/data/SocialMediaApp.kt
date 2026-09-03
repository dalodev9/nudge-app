package com.nudge.app.data

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

    companion object {
        fun findByPackageName(packageName: String): SocialMediaApp? {
            return entries.firstOrNull { it.packageNames.contains(packageName) }
        }
    }
}

