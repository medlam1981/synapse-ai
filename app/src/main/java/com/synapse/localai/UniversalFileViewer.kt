package com.synapse.localai

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.synapse.localai.StreamDemultiplexer.DemuxBlock
import com.synapse.localai.ui.theme.surfaceContainer
import com.synapse.localai.ui.theme.surfaceContainerLow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun UniversalFilePreviewCard(file: DemuxBlock.FileArtifact) {
    val context = LocalContext.current
    var preview by remember(file.executionId) { mutableStateOf<ParsedFilePreview?>(null) }
    var parseError by remember(file.executionId) { mutableStateOf<String?>(null) }
    var showViewer by rememberSaveable(file.executionId) { mutableStateOf(false) }
    val source = remember(file.path, context) { resolvePreviewFile(file.path, context) }
    val supportedFormats = setOf(
        "xlsx", "csv", "docx", "pdf", "svg", "png", "jpg", "jpeg", "webp", "gif",
        "mp3", "m4a", "wav", "ogg", "json", "xml", "yaml", "yml", "toml", "md",
        "txt", "log", "kt", "kts", "java", "py", "sh", "bash", "js", "ts",
        "html", "css", "sql", "c", "cpp", "h", "hpp", "rs", "go", "rb", "swift", "dart"
    )
    val isSupported = file.format.lowercase() in supportedFormats

    LaunchedEffect(source, isSupported) {
        if (source != null && isSupported) {
            try {
                preview = withContext(Dispatchers.IO) { FilePreviewParser.parse(source) }
                parseError = null
            } catch (error: Exception) {
                parseError = error.message ?: "The file could not be previewed."
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = source != null && isSupported) { showViewer = true }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = file.format.uppercase(Locale.ROOT).take(5),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 7.dp, vertical = 5.dp)
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        file.fileName,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        source?.let { "${fileSizeLabel(it.length())} · ${file.format.uppercase(Locale.ROOT)}" }
                            ?: "File unavailable",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(
                    enabled = source != null,
                    onClick = { openWithExternalApp(context, source, file.format) }
                ) { Text("Open") }
            }
            when {
                preview != null -> FileMicroPreview(
                    parsed = preview!!,
                    fallback = file.preview,
                    source = source,
                    format = file.format
                )
                parseError != null -> Text(
                    parseError!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                file.preview.isNotBlank() -> Text(
                    file.preview.take(220),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                else -> Text(
                    "Tap to preview",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp
                )
            }
        }
    }

    if (showViewer && source != null) {
        UniversalFileViewerDialog(
            title = file.fileName,
            source = source,
            fileFormat = file.format,
            parsed = preview,
            onClose = { showViewer = false }
        )
    }
}

@Composable
private fun FileMicroPreview(
    parsed: ParsedFilePreview,
    fallback: String,
    source: File?,
    format: String
) {
    val isImage = format.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")
    var thumbnail by remember(source) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(source, isImage) {
        if (source != null && isImage) {
            thumbnail = withContext(Dispatchers.IO) { decodeSampledBitmap(source, 256) }
        }
    }
    if (isImage && thumbnail != null) {
        Image(
            bitmap = thumbnail!!.asImageBitmap(),
            contentDescription = "Preview of ${source?.name}",
            modifier = Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop
        )
        return
    }
    when {
        parsed.sheets.isNotEmpty() -> {
            val rows = parsed.sheets.first().rows.take(4)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                rows.forEachIndexed { rowIndex, row ->
                    Text(
                        row.take(6).joinToString("  │  ").take(180),
                        color = if (rowIndex == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        parsed.text.isNotBlank() -> Text(
            parsed.text.lineSequence().take(3).joinToString("\n").take(240),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            fontFamily = if (parsed.format in setOf("docx", "txt", "md")) FontFamily.Default else FontFamily.Monospace,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        fallback.isNotBlank() -> Text(
            fallback.take(220),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        else -> Text(
            "Preview ready",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun UniversalFileViewerDialog(
    title: String,
    source: File,
    fileFormat: String,
    parsed: ParsedFilePreview?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var search by rememberSaveable(source.absolutePath) { mutableStateOf("") }
    var zoom by rememberSaveable(source.absolutePath) { mutableIntStateOf(100) }
    var activeSheet by rememberSaveable(source.absolutePath) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mimeFor(fileFormat))
    ) { destination ->
        if (destination != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(destination)?.use { output ->
                            source.inputStream().use { input -> input.copyTo(output) }
                        } ?: error("Could not open the export destination.")
                    }
                    android.widget.Toast.makeText(context, "Exported ${source.name}", android.widget.Toast.LENGTH_SHORT).show()
                } catch (failure: Exception) {
                    android.widget.Toast.makeText(
                        context,
                        "Export failed: ${failure.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                        Text(
                            "${fileFormat.uppercase(Locale.ROOT)} · ${fileSizeLabel(source.length())}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                    IconButton(onClick = { zoom = (zoom - 10).coerceAtLeast(60) }) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out")
                    }
                    Text("$zoom%", fontSize = 10.sp)
                    IconButton(onClick = { zoom = (zoom + 10).coerceAtMost(180) }) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in")
                    }
                    IconButton(onClick = {
                        shareFile(context, source, fileFormat)
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share file")
                    }
                    IconButton(onClick = { exportLauncher.launch(source.name) }) {
                        Icon(Icons.Default.Download, contentDescription = "Export file")
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close viewer")
                    }
                }

                when {
                    parsed == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Loading preview…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    parsed.sheets.isNotEmpty() -> {
                        if (parsed.sheets.size > 1) {
                            ScrollableSheetTabs(
                                sheets = parsed.sheets,
                                selectedIndex = activeSheet.coerceIn(parsed.sheets.indices),
                                onSelect = { activeSheet = it }
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            BasicTextField(
                                value = search,
                                onValueChange = { search = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp
                                ),
                                decorationBox = { inner ->
                                    Box {
                                        if (search.isEmpty()) Text("Search rows or cells", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                        inner()
                                    }
                                }
                            )
                        }
                        SpreadsheetViewer(
                            sheetData = parsed.sheets[activeSheet.coerceIn(parsed.sheets.indices)],
                            search = search,
                            zoom = zoom
                        )
                    }
                    parsed.text.isNotBlank() -> TextFileViewer(
                        content = parsed.text,
                        source = source,
                        fileFormat = fileFormat,
                        zoom = zoom,
                        onCopy = { clipboard.setText(AnnotatedString(parsed.text)) }
                    )
                    fileFormat.lowercase() == "pdf" -> PdfFileViewer(source, zoom)
                    fileFormat.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif") -> ImageFileViewer(source, zoom)
                    fileFormat.lowercase() == "svg" -> SvgFileViewer(source)
                    fileFormat.lowercase() in setOf("mp3", "m4a", "wav", "ogg") -> AudioFileViewer(source)
                    else -> UnsupportedFileViewer(context, source, fileFormat)
                }
            }
        }
    }
}

@Composable
fun SpreadsheetViewer(sheetData: PreviewSheet, search: String, zoom: Int) {
    val horizontalState = remember(sheetData) { ScrollState(0) }
    val filteredRows = remember(sheetData, search) {
        if (search.isBlank() || sheetData.rows.isEmpty()) sheetData.rows.drop(1)
        else sheetData.rows.drop(1).filter { row -> row.any { it.contains(search, ignoreCase = true) } }
    }
    val header = sheetData.rows.firstOrNull().orEmpty()
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(horizontalState)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(vertical = 2.dp)
        ) {
            header.forEach { cell ->
                Text(
                    cell,
                    modifier = Modifier.widthIn(min = 110.dp, max = 280.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                    fontSize = (11 * zoom / 100f).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (sheetData.wasTruncated) {
            Text(
                "Preview is windowed to the first ${sheetData.rows.size} rows / 5,000 cells.",
                color = MaterialTheme.colorScheme.tertiary,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(filteredRows) { index, row ->
                var expandedCell by remember { mutableStateOf<String?>(null) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(horizontalState)
                        .background(
                            if (index % 2 == 0) MaterialTheme.colorScheme.surface
                            else MaterialTheme.colorScheme.surfaceContainerLow
                        )
                ) {
                    repeat(maxOf(header.size, row.size)) { column ->
                        val cell = row.getOrElse(column) { "" }
                        Text(
                            text = cell,
                            modifier = Modifier
                                .widthIn(min = 110.dp, max = 280.dp)
                                .clickable { expandedCell = cell }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = (11 * zoom / 100f).sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (expandedCell != null) {
                    AlertDialog(
                        onDismissRequest = { expandedCell = null },
                        title = { Text("Cell value") },
                        text = { Text(expandedCell.orEmpty(), modifier = Modifier.heightIn(max = 360.dp)) },
                        confirmButton = { TextButton(onClick = { expandedCell = null }) { Text("Close") } }
                    )
                }
            }
            if (filteredRows.isEmpty()) {
                item {
                    Text(
                        "No matching rows",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScrollableSheetTabs(
    sheets: List<PreviewSheet>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        sheets.forEachIndexed { index, sheet ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                text = { Text(sheet.name, maxLines = 1) }
            )
        }
    }
}

@Composable
private fun TextFileViewer(
    content: String,
    source: File,
    fileFormat: String,
    zoom: Int,
    onCopy: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Copy")
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            itemsIndexed(content.lines()) { index, line ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "${index + 1}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        modifier = Modifier.width(42.dp),
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        line,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = (12 * zoom / 100f).sp,
                        fontFamily = if (fileFormat.lowercase() in setOf("docx", "md", "txt")) FontFamily.Default else FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageFileViewer(source: File, zoom: Int) {
    var bitmap by remember(source) { mutableStateOf<Bitmap?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    LaunchedEffect(source) {
        try {
            bitmap = withContext(Dispatchers.IO) {
                decodeSampledBitmap(source, 2048) ?: error("Image decoder returned no bitmap.")
            }
            error = null
        } catch (failure: Exception) {
            error = failure.message ?: "Image preview failed."
        }
    }
    DisposableEffect(bitmap) {
        onDispose { bitmap?.recycle() }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        bitmap?.let {
            ZoomableBitmap(it, source.name, zoom)
        } ?: Text(error ?: "Loading image…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ZoomableBitmap(bitmap: Bitmap, description: String, initialZoom: Int) {
    var gestureScale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        gestureScale = (gestureScale * zoomChange).coerceIn(0.5f, 4f)
        offset += panChange
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = description,
        modifier = Modifier
            .fillMaxSize()
            .transformable(transformState)
            .graphicsLayer {
                val scale = gestureScale * initialZoom / 100f
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun SvgFileViewer(source: File) {
    var svg by remember(source) { mutableStateOf<String?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    LaunchedEffect(source) {
        try {
            svg = withContext(Dispatchers.IO) {
                require(source.length() <= 2L * 1024 * 1024) { "SVG exceeds the 2 MB preview limit." }
                val text = source.readText(Charsets.UTF_8)
                require(!Regex("""(?is)<\s*(script|foreignObject)|<!DOCTYPE|<!ENTITY|on[a-z]+\s*=|(?:href|src)\s*=\s*["']\s*(?:https?:|file:|//)""")
                    .containsMatchIn(text)) { "SVG contains active or external content and was blocked." }
                require(text.contains("<svg", ignoreCase = true)) { "Invalid SVG document." }
                text
            }
        } catch (failure: Exception) {
            error = failure.message ?: "SVG preview failed."
        }
    }
    val content = svg
    if (content == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error ?: "Loading SVG…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    webViewClient = object : WebViewClient() {}
                    loadDataWithBaseURL(null, content, "image/svg+xml", "UTF-8", null)
                }
            }
        )
    }
}

@Composable
private fun PdfFileViewer(source: File, zoom: Int) {
    var pageCount by remember(source) { mutableIntStateOf(0) }
    var pageIndex by remember(source) { mutableIntStateOf(0) }
    var bitmap by remember(source, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    LaunchedEffect(source, pageIndex) {
        try {
            val (pageBitmap, count) = withContext(Dispatchers.IO) {
                val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
                descriptor.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        require(renderer.pageCount > 0) { "PDF has no pages." }
                        val index = pageIndex.coerceIn(0, renderer.pageCount - 1)
                        renderer.openPage(index).use { page ->
                            val scale = minOf(1800f / page.width, 1800f / page.height, 2f)
                            val image = Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888
                            )
                            page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            image to renderer.pageCount
                        }
                    }
                }
            }
            bitmap?.recycle()
            bitmap = pageBitmap
            pageCount = count
            error = null
        } catch (failure: Exception) {
            error = failure.message ?: "PDF page could not be rendered."
        }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { pageIndex = (pageIndex - 1).coerceAtLeast(0) }, enabled = pageIndex > 0) {
                Text("Previous")
            }
            Text("${pageIndex + 1} / ${pageCount.coerceAtLeast(1)}", fontSize = 11.sp)
            TextButton(
                onClick = { pageIndex = (pageIndex + 1).coerceAtMost((pageCount - 1).coerceAtLeast(0)) },
                enabled = pageCount > 0 && pageIndex < pageCount - 1
            ) { Text("Next") }
        }
        bitmap?.let {
            Box(Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
                ZoomableBitmap(it, "PDF page ${pageIndex + 1}", zoom)
            }
        } ?: Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(error ?: "Rendering PDF page…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    DisposableEffect(Unit) {
        onDispose { bitmap?.recycle() }
    }
}

@Composable
private fun AudioFileViewer(source: File) {
    var player by remember(source) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(source) { mutableStateOf(false) }
    var prepared by remember(source) { mutableStateOf(false) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    DisposableEffect(source) {
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        try {
            mediaPlayer.setDataSource(source.absolutePath)
            mediaPlayer.setOnPreparedListener { prepared = true }
            mediaPlayer.setOnCompletionListener { playing = false }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                error = "Audio playback failed."
                true
            }
            mediaPlayer.prepareAsync()
        } catch (failure: Exception) {
            error = failure.message ?: "Audio playback failed."
        }
        onDispose {
            mediaPlayer.release()
            player = null
            playing = false
            prepared = false
        }
    }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(source.name, color = MaterialTheme.colorScheme.onSurface)
        IconButton(
            enabled = prepared,
            onClick = {
                player?.let {
                    try {
                        if (it.isPlaying) {
                            it.pause()
                            playing = false
                        } else {
                            it.start()
                            playing = true
                        }
                    } catch (failure: IllegalStateException) {
                        error = failure.message ?: "Audio player is not ready."
                    }
                }
            }
        ) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun UnsupportedFileViewer(context: android.content.Context, source: File, format: String) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Preview for .$format is unavailable.", color = MaterialTheme.colorScheme.onSurface)
        TextButton(onClick = { openWithExternalApp(context, source, format) }) {
            Icon(Icons.Default.Download, contentDescription = null)
            Text("Open with external app")
        }
    }
}

private fun resolvePreviewFile(path: String, context: android.content.Context): File? = try {
    val documents = File(ToolExecutor.BASE_DIR, "documents").canonicalFile
    val chatPreviewDirectory = File(context.cacheDir, "atlas-chat-previews").canonicalFile
    val candidate = File(path).canonicalFile
    candidate.takeIf {
        (it.parentFile == documents || it.parentFile == chatPreviewDirectory) &&
            it.isFile && it.length() <= 50L * 1024L * 1024L
    }
} catch (_: Exception) {
    null
}

private fun openWithExternalApp(context: android.content.Context, file: File?, format: String) {
    if (file == null) return
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeFor(format))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open ${file.name}"))
    } catch (failure: Exception) {
        android.widget.Toast.makeText(
            context,
            "No compatible application: ${failure.message}",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

private fun shareFile(context: android.content.Context, file: File, format: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeFor(format)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
}

private fun mimeFor(format: String): String = when (format.lowercase()) {
    "pdf" -> "application/pdf"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "xls" -> "application/vnd.ms-excel"
    "csv" -> "text/csv"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "svg" -> "image/svg+xml"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "wav" -> "audio/wav"
    "ogg" -> "audio/ogg"
    else -> "text/plain"
}

private fun fileSizeLabel(size: Long): String = when {
    size >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0))
    size >= 1024L -> String.format(Locale.US, "%.0f KB", size / 1024.0)
    else -> "$size B"
}

private fun decodeSampledBitmap(file: File, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image dimensions." }
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sampleSize }
    )
}
