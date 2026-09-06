package com.synapse.localai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
private const val DESKTOP_VIEWPORT_JS = "javascript:(function() { var meta = document.querySelector('meta[name=\"viewport\"]'); if (!meta) { meta = document.createElement('meta'); meta.name = 'viewport'; document.head.appendChild(meta); } meta.content = 'width=1280, initial-scale=0.35, maximum-scale=2.0'; })()"
private const val MOBILE_VIEWPORT_JS = "javascript:(function() { var meta = document.querySelector('meta[name=\"viewport\"]'); if (meta) { meta.content = 'width=device-width, initial-scale=1.0'; } })()"

/**
 * MODULE 3: In-App Responsive Dual-Viewport Live Canvas (WebView Drawer)
 *
 * 1. Zero-Leak Lifecycle & Containment:
 *    On dismissal, immediately calls webView.stopLoading(), webView.pauseTimers(),
 *    and clears transient memory caches.
 *
 * 2. Header Bar Controls:
 *    [PREVIEW: webapp/index.html | Mobile / Desktop Toggle | Reload | External Chrome | Close]
 *
 * 3. Dual Viewport Simulation Modes:
 *    - Mode A (Default Mobile): Natural physical screen width.
 *    - Mode B (Desktop 1280px): Linux Chrome User-Agent, wide viewport, 1280px meta viewport JS injection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveCanvasDrawer(
    isOpen: Boolean,
    onDismiss: () -> Unit
) {
    if (!isOpen) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111827), // Obsidian dark (#111827)
        contentColor = Color(0xFFE2E8F0),
        dragHandle = null,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.95f)
    ) {
        LiveCanvasContent(onClose = onDismiss)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LiveCanvasContent(onClose: () -> Unit) {
    val context = LocalContext.current
    var isDesktopMode by remember { mutableStateOf(false) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var defaultMobileUserAgent by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // Resolve target webapp index file
    val primaryFile = File("/sdcard/Medlam-Ai/webapp/index.html")
    val fallbackFile = File("/sdcard/Medlam-Ai/index.html")
    val activeFile = if (primaryFile.exists()) primaryFile else fallbackFile
    val displayPath = if (primaryFile.exists()) "webapp/index.html" else if (fallbackFile.exists()) "index.html" else "webapp/index.html"

    // Zero-Leak Lifecycle Guardrail on disposal
    DisposableEffect(Unit) {
        onDispose {
            try {
                webViewInstance?.apply {
                    stopLoading()
                    pauseTimers()
                    clearCache(true)
                    loadUrl("about:blank")
                    destroy()
                }
                webViewInstance = null
            } catch (_: Exception) {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111827))
    ) {
        // --- Obsidian Header Control Bar ---
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF0F172A),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Title / Target path
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF00D2FF))
                    )
                    Text(
                        text = "PREVIEW: $displayPath",
                        color = Color(0xFF00D2FF),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Controls: [Mobile/Desktop Toggle | Reload | External Chrome | Close]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Dual Viewport Toggle
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                isDesktopMode = !isDesktopMode
                                webViewInstance?.let { webView ->
                                    val settings = webView.settings
                                    if (isDesktopMode) {
                                        settings.userAgentString = DESKTOP_USER_AGENT
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        settings.setSupportZoom(true)
                                        settings.builtInZoomControls = true
                                        settings.displayZoomControls = false
                                        webView.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
                                    } else {
                                        settings.userAgentString = defaultMobileUserAgent
                                        settings.useWideViewPort = false
                                        settings.loadWithOverviewMode = false
                                        webView.evaluateJavascript(MOBILE_VIEWPORT_JS, null)
                                    }
                                    webView.reload()
                                }
                            },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isDesktopMode) Color(0xFF0284C7).copy(alpha = 0.3f) else Color(0xFF1E293B),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isDesktopMode) Color(0xFF00D2FF) else Color(0xFF334155)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (isDesktopMode) Icons.Filled.Laptop else Icons.Filled.PhoneAndroid,
                                contentDescription = "Toggle Viewport",
                                tint = if (isDesktopMode) Color(0xFF00D2FF) else Color(0xFF94A3B8),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (isDesktopMode) "1280px" else "Mobile",
                                color = if (isDesktopMode) Color(0xFF00D2FF) else Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Reload Button
                    IconButton(
                        onClick = {
                            webViewInstance?.reload()
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Reload",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // External Chrome Button
                    IconButton(
                        onClick = {
                            if (activeFile.exists()) {
                                try {
                                    val uri = try {
                                        FileProvider.getUriForFile(context, "${context.packageName}.provider", activeFile)
                                    } catch (_: Exception) {
                                        Uri.fromFile(activeFile)
                                    }
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "text/html")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    // Try Chrome first
                                    val chromeIntent = Intent(intent).apply {
                                        setPackage("com.android.chrome")
                                    }
                                    try {
                                        context.startActivity(chromeIntent)
                                    } catch (_: Exception) {
                                        context.startActivity(Intent.createChooser(intent, "Open in Browser"))
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not open external browser: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "No index.html file found", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.OpenInBrowser,
                            contentDescription = "Open in Chrome",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Close Button
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close Canvas",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // --- Viewport Canvas Surface ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090D16)),
            contentAlignment = Alignment.Center
        ) {
            if (!activeFile.exists()) {
                // Empty state if webapp has not been generated yet
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = "No webapp/index.html detected",
                        color = Color(0xFFE2E8F0),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Generate web applications using /code or asking MEDLAM AI to build in /sdcard/Medlam-Ai/webapp/.",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = true
                                allowContentAccess = true
                                allowFileAccessFromFileURLs = true
                                allowUniversalAccessFromFileURLs = true
                                databaseEnabled = true
                                useWideViewPort = isDesktopMode
                                loadWithOverviewMode = isDesktopMode
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                cacheMode = WebSettings.LOAD_NO_CACHE
                            }

                            defaultMobileUserAgent = settings.userAgentString
                            if (isDesktopMode) {
                                settings.userAgentString = DESKTOP_USER_AGENT
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                    if (isDesktopMode) {
                                        view?.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
                                    } else {
                                        view?.evaluateJavascript(MOBILE_VIEWPORT_JS, null)
                                    }
                                }
                            }

                            webChromeClient = WebChromeClient()

                            loadUrl("file://${activeFile.absolutePath}")
                            webViewInstance = this
                        }
                    },
                    update = { view ->
                        webViewInstance = view
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
