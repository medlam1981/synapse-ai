package com.synapse.localai

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Universal AppLauncher:
 * Provides deterministic app launching and intent resolution.
 * Direct OS Intent-based launchIntent resolution,
 * with comprehensive multilingual package mapping (English, Arabic, French).
 */
object AppLauncher {

    private const val TAG = "AppLauncher"

    val PACKAGE_CANDIDATES: Map<String, List<String>> = mapOf(
        // Social & Messaging
        "facebook" to listOf("com.facebook.katana", "com.facebook.lite", "com.facebook.android"),
        "fb" to listOf("com.facebook.katana", "com.facebook.lite", "com.facebook.android"),
        "فيسبوك" to listOf("com.facebook.katana", "com.facebook.lite", "com.facebook.android"),
        "فيس" to listOf("com.facebook.katana", "com.facebook.lite", "com.facebook.android"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "واتساب" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "واتس" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "instagram" to listOf("com.instagram.android", "com.instagram.lite"),
        "insta" to listOf("com.instagram.android", "com.instagram.lite"),
        "انستغرام" to listOf("com.instagram.android", "com.instagram.lite"),
        "انستا" to listOf("com.instagram.android", "com.instagram.lite"),
        "messenger" to listOf("com.facebook.orca", "com.facebook.mlite"),
        "ماسينجر" to listOf("com.facebook.orca", "com.facebook.mlite"),
        "telegram" to listOf("org.telegram.messenger", "org.thunderdog.challegram"),
        "تلغرام" to listOf("org.telegram.messenger", "org.thunderdog.challegram"),
        "تليجرام" to listOf("org.telegram.messenger", "org.thunderdog.challegram"),
        "twitter" to listOf("com.twitter.android", "com.twitter.android.lite"),
        "x" to listOf("com.twitter.android", "com.twitter.android.lite"),
        "تويتر" to listOf("com.twitter.android", "com.twitter.android.lite"),
        "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        "تيك توك" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        "snapchat" to listOf("com.snapchat.android"),
        "سناب شات" to listOf("com.snapchat.android"),
        "سناب" to listOf("com.snapchat.android"),

        // Google & Browsers
        "chrome" to listOf("com.android.chrome"),
        "browser" to listOf("com.android.chrome", "org.mozilla.firefox", "com.opera.browser"),
        "كروم" to listOf("com.android.chrome"),
        "متصفح" to listOf("com.android.chrome", "org.mozilla.firefox", "com.opera.browser"),
        "youtube" to listOf("com.google.android.youtube"),
        "yt" to listOf("com.google.android.youtube"),
        "يوتيوب" to listOf("com.google.android.youtube"),
        "gmail" to listOf("com.google.android.gm"),
        "email" to listOf("com.google.android.gm"),
        "mail" to listOf("com.google.android.gm"),
        "جيميل" to listOf("com.google.android.gm"),
        "بريد" to listOf("com.google.android.gm"),
        "maps" to listOf("com.google.android.apps.maps"),
        "خرائط" to listOf("com.google.android.apps.maps"),
        "photos" to listOf("com.google.android.apps.photos", "com.android.gallery3d", "com.sec.android.gallery3d"),
        "gallery" to listOf("com.google.android.apps.photos", "com.android.gallery3d", "com.sec.android.gallery3d"),
        "صور" to listOf("com.google.android.apps.photos", "com.android.gallery3d", "com.sec.android.gallery3d"),
        "معرض" to listOf("com.google.android.apps.photos", "com.android.gallery3d", "com.sec.android.gallery3d"),
        "galerie" to listOf("com.google.android.apps.photos", "com.android.gallery3d", "com.sec.android.gallery3d"),
        "play store" to listOf("com.android.vending"),
        "store" to listOf("com.android.vending"),
        "متجر" to listOf("com.android.vending"),
        "بلاي ستور" to listOf("com.android.vending"),

        // System & Tools
        "settings" to listOf("com.android.settings"),
        "إعدادات" to listOf("com.android.settings"),
        "اعدادات" to listOf("com.android.settings"),
        "paramètres" to listOf("com.android.settings"),
        "camera" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera"),
        "camera2" to listOf("com.android.camera2", "com.android.camera"),
        "كاميرا" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera"),
        "phone" to listOf("com.android.dialer", "com.google.android.dialer"),
        "dialer" to listOf("com.android.dialer", "com.google.android.dialer"),
        "call" to listOf("com.android.dialer", "com.google.android.dialer"),
        "هاتف" to listOf("com.android.dialer", "com.google.android.dialer"),
        "اتصال" to listOf("com.android.dialer", "com.google.android.dialer"),
        "contacts" to listOf("com.android.contacts", "com.google.android.contacts"),
        "جهات الاتصال" to listOf("com.android.contacts", "com.google.android.contacts"),
        "messages" to listOf("com.google.android.apps.messaging", "com.android.mms"),
        "sms" to listOf("com.google.android.apps.messaging", "com.android.mms"),
        "رسائل" to listOf("com.google.android.apps.messaging", "com.android.mms"),
        "calendar" to listOf("com.google.android.calendar"),
        "تقويم" to listOf("com.google.android.calendar"),
        "clock" to listOf("com.google.android.deskclock", "com.android.deskclock"),
        "alarm" to listOf("com.google.android.deskclock", "com.android.deskclock"),
        "ساعة" to listOf("com.google.android.deskclock", "com.android.deskclock"),
        "منبه" to listOf("com.google.android.deskclock", "com.android.deskclock"),
        "horloge" to listOf("com.google.android.deskclock", "com.android.deskclock"),
        "calculator" to listOf("com.google.android.calculator", "com.android.calculator2"),
        "حاسبة" to listOf("com.google.android.calculator", "com.android.calculator2"),
        "files" to listOf("com.google.android.documentsui", "com.android.documentsui"),
        "file manager" to listOf("com.google.android.documentsui", "com.android.documentsui"),
        "ملفات" to listOf("com.google.android.documentsui", "com.android.documentsui"),
        "مدير الملفات" to listOf("com.google.android.documentsui", "com.android.documentsui"),

        // Media
        "spotify" to listOf("com.spotify.music"),
        "سبوتيفاي" to listOf("com.spotify.music"),
        "netflix" to listOf("com.netflix.mediaclient"),
        "نتفلكس" to listOf("com.netflix.mediaclient")
    )

    val KNOWN_PACKAGES: Map<String, String> = PACKAGE_CANDIDATES.mapValues { it.value.first() }

    /**
     * Cleans app name by stripping command verbs, polite prefixes, articles, and "app" keywords.
     */
    fun cleanAppName(raw: String): String {
        return raw.lowercase().trim()
            .replace(Regex("""^(?:can you\s+|could you\s+|please\s+|for\s+|to\s+|pour\s+|من فضلك\s+|ممكن\s+|عافاك\s+|بغيتك\s+)?(?:open|launch|start|run|ouvre|lance|lancer|ouvrir|افتح|شغل|حل|خدم)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:the|l'application|l'app|l'|application|app|تطبيق|برنامج)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:application|app|تطبيق|برنامج)$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    /**
     * Detects if a user command is a direct request to launch an app.
     * Returns the target app name if matched, or null otherwise.
     */
    fun extractAppLaunchTarget(command: String): String? {
        val clean = command.trim()
        if (clean.length > 100) return null
        val lower = clean.lowercase()
        if (lower.contains(" and ") || lower.contains(" then ") || lower.contains(" ثم ") ||
            lower.contains("write ") || lower.contains("create ") || lower.contains("code ") ||
            lower.contains("اصنع ") || lower.contains("اكتب ") || lower.contains("برمج ") ||
            lower.contains("build ") || lower.contains("make ") || lower.contains("develop ")) {
            return null
        }

        val stripped = clean
            .replace(Regex("""^(?:can you|could you|would you|please|i want to|i want you to|for|to|pour|من فضلك|ممكن|عافاك|بغيتك)\s+""", RegexOption.IGNORE_CASE), "")
            .trim()

        val patterns = listOf(
            // English: [open/launch/start/run] [the] [app] <target> [app]
            Regex("""^(?:open|launch|start|run)\s+(?:the\s+)?(?:app\s+)?([a-zA-Z0-9_\u0600-\u06FF\s]+?)(?:\s+app|\s+application)?$""", RegexOption.IGNORE_CASE),
            // French: [ouvre/lance/ouvrir/lancer] [l'application/l'app] <target>
            Regex("""^(?:ouvre|lance|ouvrir|lancer)\s+(?:l'application\s+|l'app\s+|l'\s+)?([a-zA-Z0-9_\u0600-\u06FF\s]+?)(?:\s+application|\s+app)?$""", RegexOption.IGNORE_CASE),
            // Arabic / Darija: [افتح/شغل/حل/خدم] [تطبيق/برنامج] <target>
            Regex("""^(?:افتح|شغل|حل|خدم)\s+(?:تطبيق\s+|برنامج\s+)?([a-zA-Z0-9_\u0600-\u06FF\s]+?)(?:\s+تطبيق|\s+برنامج)?$""", RegexOption.IGNORE_CASE)
        )

        for (p in patterns) {
            val match = p.find(stripped)
            if (match != null) {
                val target = match.groupValues[1].trim()
                if (target.isNotBlank() && !target.contains(".") && !target.contains("/") && !target.contains("http")) {
                    return cleanAppName(target)
                }
            }
        }
        return null
    }

    /**
     * Launches the requested application on the Android device.
     * Tries candidate packages and package manager activity label resolution via standard OS Intents.
     */
    fun launch(context: Context?, appName: String): Boolean {
        val clean = cleanAppName(appName)
        Log.i(TAG, "Attempting to launch app: '$clean' (raw: '$appName')")

        val ctx = context ?: MainActivity.instance ?: return false
        val pm = ctx.packageManager

        // Collect candidate packages in priority order
        val candidates = mutableListOf<String>()
        PACKAGE_CANDIDATES[clean]?.let { candidates.addAll(it) }
        PACKAGE_CANDIDATES[clean.replace(" ", "")]?.let { candidates.addAll(it) }
        KNOWN_PACKAGES[clean]?.let { if (!candidates.contains(it)) candidates.add(it) }
        KNOWN_PACKAGES[clean.replace(" ", "")]?.let { if (!candidates.contains(it)) candidates.add(it) }

        if (clean.contains(".")) {
            candidates.add(clean)
        }

        // 1. Try launching through Context PackageManager candidates
        for (pkg in candidates) {
            try {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    ctx.startActivity(intent)
                    Log.i(TAG, "Successfully launched candidate package via Intent: $pkg")
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Candidate $pkg launch check failed: ${e.message}")
            }
        }

        // 2. Query launcher activities dynamically
        try {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = pm.queryIntentActivities(launcherIntent, 0)
            val match = activities.find { 
                it.loadLabel(pm).toString().equals(clean, ignoreCase = true) 
            } ?: activities.find {
                it.loadLabel(pm).toString().contains(clean, ignoreCase = true)
            } ?: activities.find {
                it.activityInfo.packageName.contains(clean, ignoreCase = true)
            }

            if (match != null) {
                val pkg = match.activityInfo.packageName
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    ctx.startActivity(intent)
                    Log.i(TAG, "Successfully launched query activity via Intent: $pkg")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed querying launcher activities: ${e.message}")
        }

        Log.w(TAG, "All launch strategies exhausted for app: '$clean'")
        return false
    }
}
