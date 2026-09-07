package com.synapse.localai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * AppLauncherManager — Autonomous Zero-Fail Android Application Launcher & Dynamic Package Resolver.
 *
 * Implements:
 * 1. Common Package Alias Map (English, Arabic, French, colloquial aliases).
 * 2. Dynamic PackageManager Resolution (label matching against localized installed apps).
 * 3. Shell Execution Fallback (pm list packages | grep + monkey / am start via root or sh).
 * 4. Zero-Failure Fallback (web deep-link or market://details?id=... if app is genuinely uninstalled).
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

    // Fallback URLs for web deep-link resolution when app is genuinely missing
    private val WEB_FALLBACK_URLS = mapOf(
        "com.google.android.youtube" to "https://m.youtube.com",
        "com.whatsapp" to "https://web.whatsapp.com",
        "org.telegram.messenger" to "https://web.telegram.org",
        "com.facebook.katana" to "https://m.facebook.com",
        "com.instagram.android" to "https://www.instagram.com",
        "com.twitter.android" to "https://mobile.twitter.com",
        "com.zhiliaoapp.musically" to "https://www.tiktok.com",
        "com.google.android.apps.maps" to "https://maps.google.com",
        "com.google.android.gm" to "https://mail.google.com",
        "com.android.chrome" to "https://www.google.com"
    )

    /**
     * Resolves target string to a candidate Android package name using:
     * 1. Deterministic alias map
     * 2. Camera package detection
     * 3. Dynamic PackageManager matching (labels and package names)
     * 4. Shell `pm list packages` search
     */
    fun resolvePackageName(target: String, pm: PackageManager?): String {
        val clean = target.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        val lower = clean.lowercase(Locale.ROOT)

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
     * Tier 2: Shell fallback (monkey / am start via root or sh)
     * Tier 3: Zero-Failure Fallback (web deep-link or market://)
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
            Log.w(TAG, "Tier 1 standard launch failed: ${e.message}, escalating to Tier 2 (Shell)")
        }

        // ── Tier 2: Shell Execution Fallback (Root / ADB / sh) ───────────────
        try {
            val shellResult = launchViaShell(resolvedPkg, target)
            if (shellResult) {
                friendlyLabel = try {
                    val appInfo = pm.getApplicationInfo(resolvedPkg, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    resolvedPkg
                }
                Log.i(TAG, "Tier 2: Successfully launched $friendlyLabel ($resolvedPkg) via shell fallback")
                return LaunchResult(
                    success = true,
                    appLabel = friendlyLabel,
                    packageName = resolvedPkg,
                    method = "SHELL_FALLBACK"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 2 shell launch failed: ${e.message}, escalating to Tier 3 (Zero-Fail Web/Store)")
        }

        // ── Tier 3: Zero-Failure Fallback (Web Deep-Link / Play Store) ───────
        val fallbackUrl = WEB_FALLBACK_URLS[resolvedPkg]
        if (fallbackUrl != null) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(browserIntent)
                Log.i(TAG, "Tier 3: Launched web fallback $fallbackUrl for $resolvedPkg")
                return LaunchResult(
                    success = true,
                    appLabel = "$resolvedPkg (Web Service)",
                    packageName = resolvedPkg,
                    method = "WEB_FALLBACK"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Web fallback intent failed: ${e.message}")
            }
        }

        // Play Store Deep-Link
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$resolvedPkg")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(marketIntent)
            Log.i(TAG, "Tier 3: Launched Play Store deep-link for $resolvedPkg")
            return LaunchResult(
                success = true,
                appLabel = "$resolvedPkg (Play Store)",
                packageName = resolvedPkg,
                method = "STORE_DEEP_LINK"
            )
        } catch (_: Exception) {
            try {
                val webStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$resolvedPkg")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(webStoreIntent)
                return LaunchResult(
                    success = true,
                    appLabel = "$resolvedPkg (Web Store)",
                    packageName = resolvedPkg,
                    method = "STORE_WEB_FALLBACK"
                )
            } catch (err: Exception) {
                return LaunchResult(
                    success = false,
                    appLabel = target,
                    packageName = resolvedPkg,
                    method = "FAILED",
                    errorMessage = err.message ?: "Unable to launch application"
                )
            }
        }
    }

    /**
     * Executes shell launch fallback using monkey and am start.
     */
    private fun launchViaShell(pkg: String, originalTarget: String): Boolean {
        val cleanPkg = pkg.trim()
        val cleanTarget = originalTarget.filter { it.isLetterOrDigit() || it == '.' || it == '_' }

        val script = "TARGET_PKG=\"$cleanPkg\"\n" +
            "if ! pm list packages | grep -q \"$cleanPkg\"; then\n" +
            "    FOUND_PKG=\$(pm list packages | grep -i \"$cleanTarget\" | head -n 1 | cut -d: -f2)\n" +
            "    if [ -n \"\$FOUND_PKG\" ]; then\n" +
            "        TARGET_PKG=\"\$FOUND_PKG\"\n" +
            "    fi\n" +
            "fi\n" +
            "if pm list packages | grep -q \"\$TARGET_PKG\"; then\n" +
            "    monkey -p \"\$TARGET_PKG\" -c android.intent.category.LAUNCHER 1 2>/dev/null || am start -n \"\$TARGET_PKG\" 2>/dev/null\n" +
            "    exit 0\n" +
            "else\n" +
            "    exit 1\n" +
            "fi\n"

        return try {
            val pb = ProcessBuilder("sh", "-c", script)
            val proc = pb.start()
            val exited = proc.waitFor(3, TimeUnit.SECONDS)
            exited && proc.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }
}
