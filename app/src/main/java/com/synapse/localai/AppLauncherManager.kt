package com.synapse.localai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * AppLauncherManager — Autonomous Android Application Launcher & Dynamic Package Resolver.
 *
 * Implements:
 * 1. Strict YouTube package mapping (com.google.android.youtube).
 * 2. Common Package Alias Map (English, Arabic, French, colloquial aliases).
 * 3. Dynamic PackageManager Resolution (label matching against localized installed apps).
 * 4. Direct Shell Execution Fallback (am start || monkey) without web/store redirection.
 */
object AppLauncherManager {

    private const val TAG = "AppLauncherManager"

    data class LaunchResult(
        val success: Boolean,
        val appLabel: String,
        val packageName: String,
        val method: String,
        val errorMessage: String? = null
    )

    // 1. Common Package Alias Map
    private val ALIAS_MAP = mapOf(
        // YouTube
        "youtube" to "com.google.android.youtube",
        "يوتيوب" to "com.google.android.youtube",
        "اليوتيوب" to "com.google.android.youtube",
        "yt" to "com.google.android.youtube",

        // WhatsApp
        "whatsapp" to "com.whatsapp",
        "واتساب" to "com.whatsapp",
        "واتس" to "com.whatsapp",
        "الواتساب" to "com.whatsapp",
        "whatsapp business" to "com.whatsapp.w4b",
        "واتساب اعمال" to "com.whatsapp.w4b",

        // Chrome / Web Browsers
        "chrome" to "com.android.chrome",
        "كروم" to "com.android.chrome",
        "جوجل كروم" to "com.android.chrome",
        "google chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "متصفح" to "com.android.chrome",
        "المتصفح" to "com.android.chrome",
        "firefox" to "org.mozilla.firefox",
        "فايرفوكس" to "org.mozilla.firefox",
        "edge" to "com.microsoft.emmx",

        // Telegram
        "telegram" to "org.telegram.messenger",
        "تيليجرام" to "org.telegram.messenger",
        "تليجرام" to "org.telegram.messenger",
        "التيليجرام" to "org.telegram.messenger",

        // Settings
        "settings" to "com.android.settings",
        "الاعدادات" to "com.android.settings",
        "الإعدادات" to "com.android.settings",
        "الضبط" to "com.android.settings",
        "إعدادات" to "com.android.settings",
        "اعدادات" to "com.android.settings",

        // Camera
        "camera" to "CAMERA_ALIAS",
        "كاميرا" to "CAMERA_ALIAS",
        "الكاميرا" to "CAMERA_ALIAS",
        "تصوير" to "CAMERA_ALIAS",
        "cam" to "CAMERA_ALIAS",

        // Social Media
        "facebook" to "com.facebook.katana",
        "فيسبوك" to "com.facebook.katana",
        "فيس" to "com.facebook.katana",
        "فايسبوك" to "com.facebook.katana",
        "fb" to "com.facebook.katana",
        "instagram" to "com.instagram.android",
        "انستغرام" to "com.instagram.android",
        "انستقرام" to "com.instagram.android",
        "انستا" to "com.instagram.android",
        "ig" to "com.instagram.android",
        "tiktok" to "com.zhiliaoapp.musically",
        "تيك توك" to "com.zhiliaoapp.musically",
        "تيكتوك" to "com.zhiliaoapp.musically",
        "twitter" to "com.twitter.android",
        "تويتر" to "com.twitter.android",
        "x" to "com.twitter.android",

        // Google Apps & System Utilities
        "maps" to "com.google.android.apps.maps",
        "خرائط" to "com.google.android.apps.maps",
        "خرائط جوجل" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "gmail" to "com.google.android.gm",
        "جيميل" to "com.google.android.gm",
        "بريد" to "com.google.android.gm",
        "البريد" to "com.google.android.gm",
        "email" to "com.google.android.gm",
        "mail" to "com.google.android.gm",
        "play store" to "com.android.vending",
        "متجر بلاي" to "com.android.vending",
        "بلاي ستور" to "com.android.vending",
        "google play" to "com.android.vending",
        "store" to "com.android.vending",
        "photos" to "com.google.android.apps.photos",
        "الصور" to "com.google.android.apps.photos",
        "gallery" to "com.google.android.apps.photos",
        "الاستوديو" to "com.google.android.apps.photos",
        "معرض الصور" to "com.google.android.apps.photos",
        "calculator" to "com.google.android.calculator",
        "الحاسبة" to "com.google.android.calculator",
        "حاسبة" to "com.google.android.calculator",
        "clock" to "com.google.android.deskclock",
        "الساعة" to "com.google.android.deskclock",
        "المنبه" to "com.google.android.deskclock",
        "contacts" to "com.google.android.contacts",
        "جهات الاتصال" to "com.google.android.contacts",
        "الاسماء" to "com.google.android.contacts",
        "messages" to "com.google.android.apps.messaging",
        "الرسائل" to "com.google.android.apps.messaging",
        "رسائل" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging",
        "phone" to "com.google.android.dialer",
        "الهاتف" to "com.google.android.dialer",
        "الاتصال" to "com.google.android.dialer",
        "dialer" to "com.google.android.dialer",
        "files" to "com.google.android.documentsui",
        "الملفات" to "com.google.android.documentsui",
        "مدير الملفات" to "com.google.android.documentsui",
        "file manager" to "com.google.android.documentsui"
    )

    /**
     * Resolves target string to a candidate Android package name using:
     * 1. Strict YouTube rule
     * 2. Deterministic alias map
     * 3. Camera package detection
     * 4. Dynamic PackageManager matching (labels and package names)
     * 5. Shell `pm list packages` search
     */
    fun resolvePackageName(target: String, pm: PackageManager?): String {
        val clean = target.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        val lower = clean.lowercase(Locale.ROOT)

        // Strict YouTube rule: map youtube strictly to com.google.android.youtube
        if (lower == "youtube" || lower == "yt" || lower == "يوتيوب" || lower == "اليوتيوب" || lower.contains("youtube") || lower.contains("يوتيوب")) {
            return "com.google.android.youtube"
        }

        // 1. Direct Alias Lookup
        val alias = ALIAS_MAP[lower] ?: ALIAS_MAP[clean]
        if (alias != null) {
            if (alias == "CAMERA_ALIAS") {
                return resolveCameraPackage(pm)
            }
            return alias
        }

        // 2. Exact package pattern (e.g. com.synapse.localai)
        if (clean.contains(".") && !clean.contains(" ")) {
            val pkg = if (clean.contains("/")) clean.substringBefore("/") else clean
            return pkg
        }

        // 3. Dynamic PackageManager Resolution via Launcher Activities & Installed Apps
        if (pm != null) {
            try {
                // Match launcher activities
                val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
                for (ri in resolveInfos) {
                    val label = ri.loadLabel(pm).toString().lowercase(Locale.ROOT)
                    if (label == lower || label.contains(lower) || lower.contains(label)) {
                        return ri.activityInfo.packageName
                    }
                }

                // Match installed applications
                val installedApps = pm.getInstalledApplications(0)
                for (appInfo in installedApps) {
                    val label = appInfo.loadLabel(pm).toString().lowercase(Locale.ROOT)
                    if (label == lower || label.contains(lower) || lower.contains(label)) {
                        return appInfo.packageName
                    }
                    if (appInfo.packageName.lowercase(Locale.ROOT).contains(lower)) {
                        return appInfo.packageName
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "PackageManager query failed: ${e.message}")
            }
        }

        // 4. Shell search fallback via pm list packages
        val shellPkg = findPackageViaShell(clean)
        if (shellPkg != null) {
            return shellPkg
        }

        return clean
    }

    /**
     * Finds installed camera package dynamically.
     */
    private fun resolveCameraPackage(pm: PackageManager?): String {
        if (pm != null) {
            try {
                val camIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                val resolved = pm.resolveActivity(camIntent, PackageManager.MATCH_DEFAULT_ONLY)
                if (resolved != null && resolved.activityInfo.packageName.isNotBlank()) {
                    return resolved.activityInfo.packageName
                }
            } catch (_: Exception) {}

            val candidateCameraPackages = listOf(
                "com.google.android.apps.cameralite",
                "com.android.camera2",
                "com.google.android.GoogleCamera",
                "com.sec.android.app.camera",
                "com.android.camera"
            )
            for (pkg in candidateCameraPackages) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    return pkg
                } catch (_: Exception) {}
            }
        }
        return "com.android.camera2"
    }

    /**
     * Queries package list via shell for substring matches.
     */
    private fun findPackageViaShell(keyword: String): String? {
        val cleanKeyword = keyword.filter { it.isLetterOrDigit() || it == '.' || it == '_' }
        if (cleanKeyword.length < 3) return null

        return try {
            val cmd = "pm list packages | grep -i \"$cleanKeyword\" | head -n 1 | cut -d: -f2"
            val pb = ProcessBuilder("sh", "-c", cmd)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor(2, TimeUnit.SECONDS)
            if (output.isNotBlank() && output.contains(".") && !output.contains(" ")) {
                output
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Executes bulletproof application launch.
     * Tier 1: Standard Intent
     * Tier 2: Direct Shell Execution (am start || monkey)
     * All web deep-links and Play Store redirects are strictly banned.
     */
    fun launch(target: String, context: Context?): LaunchResult {
        val ctx = context ?: MainActivity.instance ?: return LaunchResult(
            success = false,
            appLabel = target,
            packageName = target,
            method = "NATIVE",
            errorMessage = "Android Context unavailable"
        )

        val pm = ctx.packageManager
        val resolvedPkg = resolvePackageName(target, pm)
        var friendlyLabel = target

        // ── Tier 1: Standard Android Launch Intent ───────────────────────────
        try {
            val launchIntent = pm.getLaunchIntentForPackage(resolvedPkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            if (launchIntent != null) {
                ctx.startActivity(launchIntent)
                friendlyLabel = try {
                    val appInfo = pm.getApplicationInfo(resolvedPkg, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    resolvedPkg
                }
                Log.i(TAG, "Tier 1: Successfully launched $friendlyLabel ($resolvedPkg) natively")
                return LaunchResult(
                    success = true,
                    appLabel = friendlyLabel,
                    packageName = resolvedPkg,
                    method = "NATIVE_INTENT"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 1 standard launch failed: ${e.message}, escalating to direct shell launch")
        }

        // ── Tier 2: Direct Shell Execution Fallback (am start || monkey) ───────────
        try {
            val shellResult = launchViaDirectShell(resolvedPkg, target)
            if (shellResult) {
                friendlyLabel = try {
                    val appInfo = pm.getApplicationInfo(resolvedPkg, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    resolvedPkg
                }
                Log.i(TAG, "Tier 2: Successfully launched $friendlyLabel ($resolvedPkg) via direct shell")
                return LaunchResult(
                    success = true,
                    appLabel = friendlyLabel,
                    packageName = resolvedPkg,
                    method = "DIRECT_SHELL"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 2 direct shell launch failed: ${e.message}")
        }

        // Banned Web & Play Store Fallbacks - return clean failure
        Log.e(TAG, "Failed to launch $resolvedPkg natively and via direct shell execution")
        return LaunchResult(
            success = false,
            appLabel = target,
            packageName = resolvedPkg,
            method = "FAILED",
            errorMessage = "Failed to launch package '$resolvedPkg' directly on device."
        )
    }

    /**
     * Executes direct shell launch using am start and monkey:
     * am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$TARGET_PKG" 2>/dev/null || monkey -p "$TARGET_PKG" -c android.intent.category.LAUNCHER 1 2>/dev/null
     */
    private fun launchViaDirectShell(pkg: String, originalTarget: String): Boolean {
        val cleanPkg = pkg.trim()
        val cleanTarget = originalTarget.filter { it.isLetterOrDigit() || it == '.' || it == '_' }

        val script = "TARGET_PKG=\"$cleanPkg\"\n" +
            "if ! pm list packages | grep -q \"^package:$cleanPkg\$\"; then\n" +
            "    FOUND_PKG=\$(pm list packages | grep -i \"$cleanTarget\" | head -n 1 | cut -d: -f2)\n" +
            "    if [ -n \"\$FOUND_PKG\" ]; then\n" +
            "        TARGET_PKG=\"\$FOUND_PKG\"\n" +
            "    fi\n" +
            "fi\n" +
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p \"\$TARGET_PKG\" 2>/dev/null || monkey -p \"\$TARGET_PKG\" -c android.intent.category.LAUNCHER 1 2>/dev/null\n"

        return try {
            val pb = ProcessBuilder("sh", "-c", script)
            val proc = pb.start()
            val exited = proc.waitFor(4, TimeUnit.SECONDS)
            exited && proc.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }
}
