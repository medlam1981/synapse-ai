package com.synapse.localai

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Universal AppLauncher:
 * Provides deterministic app launching and intent resolution.
 * Supports AccessibilityService and fallback Context-based launchIntent,
 * with comprehensive multilingual package mapping (English, Arabic, French).
 */
object AppLauncher {

    private const val TAG = "AppLauncher"

    val KNOWN_PACKAGES: Map<String, String> = mapOf(
        // Social & Messaging
        "facebook" to "com.facebook.katana",
        "fb" to "com.facebook.katana",
        "فيسبوك" to "com.facebook.katana",
        "فيس" to "com.facebook.katana",
        "whatsapp" to "com.whatsapp",
        "واتساب" to "com.whatsapp",
        "واتس" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "insta" to "com.instagram.android",
        "انستغرام" to "com.instagram.android",
        "انستا" to "com.instagram.android",
        "telegram" to "org.telegram.messenger",
        "تلغرام" to "org.telegram.messenger",
        "تليجرام" to "org.telegram.messenger",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "تويتر" to "com.twitter.android",
        "tiktok" to "com.zhiliaoapp.musically",
        "تيك توك" to "com.zhiliaoapp.musically",
        "snapchat" to "com.snapchat.android",
        "سناب شات" to "com.snapchat.android",
        "سناب" to "com.snapchat.android",

        // Google & Browsers
        "chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "كروم" to "com.android.chrome",
        "متصفح" to "com.android.chrome",
        "youtube" to "com.google.android.youtube",
        "yt" to "com.google.android.youtube",
        "يوتيوب" to "com.google.android.youtube",
        "gmail" to "com.google.android.gm",
        "email" to "com.google.android.gm",
        "mail" to "com.google.android.gm",
        "جيميل" to "com.google.android.gm",
        "بريد" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "خرائط" to "com.google.android.apps.maps",
        "photos" to "com.google.android.apps.photos",
        "gallery" to "com.google.android.apps.photos",
        "صور" to "com.google.android.apps.photos",
        "معرض" to "com.google.android.apps.photos",
        "galerie" to "com.google.android.apps.photos",
        "play store" to "com.android.vending",
        "store" to "com.android.vending",
        "متجر" to "com.android.vending",
        "بلاي ستور" to "com.android.vending",

        // System & Tools
        "settings" to "com.android.settings",
        "إعدادات" to "com.android.settings",
        "اعدادات" to "com.android.settings",
        "paramètres" to "com.android.settings",
        "camera" to "com.android.camera",
        "camera2" to "com.android.camera2",
        "كاميرا" to "com.android.camera",
        "phone" to "com.android.dialer",
        "dialer" to "com.android.dialer",
        "call" to "com.android.dialer",
        "هاتف" to "com.android.dialer",
        "اتصال" to "com.android.dialer",
        "contacts" to "com.android.contacts",
        "جهات الاتصال" to "com.android.contacts",
        "messages" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging",
        "رسائل" to "com.google.android.apps.messaging",
        "calendar" to "com.google.android.calendar",
        "تقويم" to "com.google.android.calendar",
        "clock" to "com.google.android.deskclock",
        "alarm" to "com.google.android.deskclock",
        "ساعة" to "com.google.android.deskclock",
        "منبه" to "com.google.android.deskclock",
        "horloge" to "com.google.android.deskclock",
        "calculator" to "com.google.android.calculator",
        "حاسبة" to "com.google.android.calculator",
        "files" to "com.google.android.documentsui",
        "file manager" to "com.google.android.documentsui",
        "ملفات" to "com.google.android.documentsui",
        "مدير الملفات" to "com.google.android.documentsui",

        // Media
        "spotify" to "com.spotify.music",
        "سبوتيفاي" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "نتفلكس" to "com.netflix.mediaclient"
    )

    /**
     * Cleans app name by stripping command verbs, articles, and "app" keywords.
     */
    fun cleanAppName(raw: String): String {
        return raw.lowercase().trim()
            .replace(Regex("""^(?:please\s+)?(?:open|launch|start|run|ouvre|lance|افتح|شغل|حل|خدم)\s+""", RegexOption.IGNORE_CASE), "")
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
        if (clean.length > 70) return null
        val lower = clean.lowercase()
        if (lower.contains(" and ") || lower.contains(" then ") || lower.contains(" ثم ") ||
            lower.contains("write ") || lower.contains("create ") || lower.contains("code ") ||
            lower.contains("اصنع ") || lower.contains("اكتب ") || lower.contains("برمج ")) {
            return null
        }

        val patterns = listOf(
            // English: [please] open/launch/start/run [the] [app] <target> [app]
            Regex("""^(?:please\s+)?(?:open|launch|start|run)\s+(?:the\s+)?(?:app\s+)?([a-zA-Z0-9_\u0600-\u06FF\s]+?)(?:\s+app|\s+application)?$""", RegexOption.IGNORE_CASE),
            // French: [s'il vous plait] ouvre/lance [l'application/l'app] <target>
            Regex("""^(?:s'il vous plait\s+)?(?:ouvre|lance)\s+(?:l'application\s+|l'app\s+|l'\s+)?([a-zA-Z0-9_\u0600-\u06FF\s]+?)(?:\s+application|\s+app)?$""", RegexOption.IGNORE_CASE),
            // Arabic / Darija: [من فضلك] افتح/شغل/حل/خدم [تطبيق/برنامج] <target>
            Regex("""^(?:من فضلك\s+)?(?:افتح|شغل|حل|خدم)\s+(?:تطبيق\s+|برنامج\s+)?([a-zA-Z0-9_\u0600-\u06FF\s]+?)(?:\s+تطبيق|\s+برنامج)?$""", RegexOption.IGNORE_CASE)
        )

        for (p in patterns) {
            val match = p.find(clean)
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
     * Tries SynapseAccessibilityService first, and falls back to Context PackageManager.
     */
    fun launch(context: Context?, appName: String): Boolean {
        val clean = cleanAppName(appName)
        Log.i(TAG, "Attempting to launch app: '$clean' (raw: '$appName')")

        // 1. Accessibility Service Execution
        val service = SynapseAccessibilityService.instance
        if (service != null && service.launchApp(clean)) {
            return true
        }

        // 2. Direct Context Activity Launch Fallback
        val ctx = context ?: MainActivity.instance ?: SynapseAccessibilityService.instance ?: return false
        val pm = ctx.packageManager

        val resolvedPkg = KNOWN_PACKAGES[clean]
            ?: KNOWN_PACKAGES[clean.replace(" ", "")]
            ?: run {
                // Check direct package name
                try {
                    pm.getPackageInfo(clean, 0)
                    clean
                } catch (_: Exception) {
                    // Search launcher activity labels
                    try {
                        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        val list = pm.queryIntentActivities(intent, 0)
                        list.find { it.loadLabel(pm).toString().equals(clean, ignoreCase = true) }?.activityInfo?.packageName
                            ?: list.find { it.loadLabel(pm).toString().contains(clean, ignoreCase = true) }?.activityInfo?.packageName
                    } catch (_: Exception) {
                        null
                    }
                }
            }

        if (resolvedPkg == null) {
            Log.w(TAG, "Could not resolve package for app name: '$clean'")
            return false
        }

        return try {
            val launchIntent = pm.getLaunchIntentForPackage(resolvedPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                ctx.startActivity(launchIntent)
                Log.i(TAG, "Launched via Context: $resolvedPkg")
                true
            } else {
                Log.w(TAG, "No launch intent found for: $resolvedPkg")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching package $resolvedPkg: ${e.message}")
            false
        }
    }
}
