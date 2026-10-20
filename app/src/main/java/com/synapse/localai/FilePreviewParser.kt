package com.synapse.localai

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

data class PreviewSheet(
    val name: String,
    val rows: List<List<String>>,
    val wasTruncated: Boolean
)

data class ParsedFilePreview(
    val format: String,
    val text: String = "",
    val sheets: List<PreviewSheet> = emptyList()
)

object FilePreviewParser {

    private const val MAX_FILE_BYTES = 50L * 1024L * 1024L
    private const val MAX_TEXT_BYTES = 2L * 1024L * 1024L
    private const val MAX_XML_ENTRY_BYTES = 8L * 1024L * 1024L
    private const val MAX_CELLS = 5_000
    private const val MAX_COLUMNS = 100

    fun parse(file: File): ParsedFilePreview {
        require(file.isFile && file.canRead()) { "File is not readable." }
        require(file.length() <= MAX_FILE_BYTES) { "Files larger than 50 MB cannot be previewed." }
        return when (file.extension.lowercase()) {
            "csv" -> ParsedFilePreview("csv", sheets = listOf(parseCsv(file)))
            "xlsx" -> ParsedFilePreview("xlsx", sheets = parseXlsx(file))
            "docx" -> ParsedFilePreview("docx", text = parseDocx(file))
            "txt", "log", "json", "xml", "yaml", "yml", "toml", "md",
            "kt", "kts", "java", "py", "sh", "bash", "js", "ts", "html", "css",
            "sql", "c", "cpp", "h", "hpp", "rs", "go", "rb", "swift", "dart" ->
                ParsedFilePreview(file.extension.lowercase(), text = readText(file))
            else -> ParsedFilePreview(file.extension.lowercase())
        }
    }

    private fun parseCsv(file: File): PreviewSheet {
        require(file.length() <= MAX_TEXT_BYTES) { "CSV files larger than 2 MB cannot be previewed." }
        val rows = mutableListOf<List<String>>()
        var totalCells = 0
        var truncated = false
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val row = mutableListOf<String>()
            val cell = StringBuilder()
            var quoted = false
            var reachedEnd = false
            while (!reachedEnd) {
                val code = reader.read()
                if (code < 0) {
                    reachedEnd = true
                } else {
                    val char = code.toChar()
                    when {
                        char == '"' && quoted -> {
                            reader.mark(1)
                            if (reader.read() == '"'.code) cell.append('"') else {
                                quoted = false
                                reader.reset()
                            }
                        }
                        char == '"' && cell.isEmpty() -> quoted = true
                        char == ',' && !quoted -> {
                            row.add(cell.toString())
                            cell.setLength(0)
                        }
                        (char == '\n' || char == '\r') && !quoted -> {
                            if (char == '\r') {
                                reader.mark(1)
                                if (reader.read() != '\n'.code) reader.reset()
                            }
                            row.add(cell.toString())
                            cell.setLength(0)
                            if (totalCells + row.size > MAX_CELLS) {
                                truncated = true
                                reachedEnd = true
                            } else {
                                rows.add(row.toList().take(MAX_COLUMNS))
                                totalCells += row.size
                            }
                            row.clear()
                        }
                        else -> cell.append(char)
                    }
                }
                if (cell.length > 100_000) error("A CSV cell exceeds the preview limit.")
            }
            if (row.isNotEmpty() || cell.isNotEmpty()) {
                row.add(cell.toString())
                if (totalCells + row.size > MAX_CELLS) truncated = true
                else rows.add(row.take(MAX_COLUMNS))
            }
            if (quoted) error("CSV contains an unterminated quoted field.")
        }
        return PreviewSheet(file.name, rows, truncated)
    }

    private fun parseXlsx(file: File): List<PreviewSheet> {
        val entries = readZipEntries(file, setOf(
            "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels",
            "xl/sharedStrings.xml"
        ), worksheetEntries = true)
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val worksheetFiles = entries.keys.filter { it.matches(Regex("""xl/worksheets/sheet\d+\.xml""")) }
            .sortedBy { it.substringAfter("sheet").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
        require(worksheetFiles.isNotEmpty()) { "Workbook contains no readable worksheets." }
        val names = entries["xl/workbook.xml"]?.let(::parseSheetNames).orEmpty()
        return worksheetFiles.mapIndexed { index, path ->
            val (rows, wasTruncated) = parseWorksheet(entries.getValue(path), sharedStrings)
            PreviewSheet(names.getOrNull(index) ?: "Sheet ${index + 1}", rows, wasTruncated)
        }
    }

    private fun readZipEntries(
        file: File,
        fixedEntries: Set<String>,
        worksheetEntries: Boolean
    ): Map<String, ByteArray> {
        val wanted = fixedEntries.toMutableSet()
        val found = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(FileInputStream(file)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && (entry.name in wanted ||
                        worksheetEntries && entry.name.matches(Regex("""xl/worksheets/sheet\d+\.xml""")))
                ) {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var entryBytes = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        entryBytes += count
                        totalBytes += count
                        require(entryBytes <= MAX_XML_ENTRY_BYTES && totalBytes <= 20L * 1024L * 1024L) {
                            "Workbook XML expands beyond the safe preview limit."
                        }
                        output.write(buffer, 0, count)
                    }
                    found[entry.name] = output.toByteArray()
                }
                zip.closeEntry()
            }
        }
        return found
    }

    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val parser = newParser(xml)
        val strings = mutableListOf<String>()
        var insideItem = false
        var current = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> {
                        insideItem = true
                        current = StringBuilder()
                    }
                    "t" -> if (insideItem) current.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.name == "si" && insideItem) {
                    strings.add(current.toString())
                    insideItem = false
                }
            }
            event = parser.next()
        }
        return strings
    }

    private fun parseSheetNames(xml: ByteArray): List<String> {
        val parser = newParser(xml)
        val names = mutableListOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                names.add(parser.getAttributeValue(null, "name") ?: "Sheet ${names.size + 1}")
            }
            event = parser.next()
        }
        return names
    }

    private fun parseWorksheet(xml: ByteArray, sharedStrings: List<String>): Pair<List<List<String>>, Boolean> {
        val parser = newParser(xml)
        val rows = mutableListOf<List<String>>()
        var currentRow: MutableList<String>? = null
        var currentCellType = ""
        var currentCellColumn = 0
        var currentValue = ""
        var cellCount = 0
        var truncated = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && !truncated) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> currentRow = mutableListOf()
                    "c" -> {
                        currentCellType = parser.getAttributeValue(null, "t").orEmpty()
                        currentCellColumn = columnIndex(parser.getAttributeValue(null, "r").orEmpty())
                        currentValue = ""
                    }
                    "v", "t" -> if (currentRow != null) currentValue = parser.nextText()
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "c" -> {
                        val row = currentRow
                        if (row != null) {
                            if (currentCellColumn >= MAX_COLUMNS) {
                                truncated = true
                            } else {
                                while (row.size <= currentCellColumn) row.add("")
                                row[currentCellColumn] = if (currentCellType == "s") {
                                    sharedStrings.getOrNull(currentValue.toIntOrNull() ?: -1).orEmpty()
                                } else {
                                    currentValue
                                }
                                cellCount++
                                if (cellCount >= MAX_CELLS) truncated = true
                            }
                        }
                    }
                    "row" -> currentRow?.let {
                        if (rows.size < MAX_CELLS) rows.add(it.toList())
                        currentRow = null
                    }
                }
            }
            event = parser.next()
        }
        return rows to truncated
    }

    private fun columnIndex(reference: String): Int {
        val letters = reference.takeWhile(Char::isLetter).uppercase()
        if (letters.isEmpty()) return 0
        var value = 0
        letters.forEach { char -> value = value * 26 + (char - 'A' + 1) }
        return value - 1
    }

    private fun parseDocx(file: File): String {
        val xml = readZipEntries(file, setOf("word/document.xml"), worksheetEntries = false)
            .get("word/document.xml") ?: error("Word document has no readable document body.")
        val parser = newParser(xml)
        val output = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && output.length < MAX_TEXT_BYTES) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name == "t") output.append(parser.nextText())
                XmlPullParser.END_TAG -> if (parser.name == "p") output.append('\n')
            }
            event = parser.next()
        }
        return output.toString().trim()
    }

    private fun readText(file: File): String {
        require(file.length() <= MAX_TEXT_BYTES) { "Text files larger than 2 MB cannot be previewed." }
        return file.inputStream().buffered().use { input ->
            val bytes = input.readBytes()
            bytes.toString(Charsets.UTF_8).take(MAX_TEXT_BYTES.toInt())
        }
    }

    private fun newParser(xml: ByteArray): XmlPullParser =
        Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(xml.inputStream(), "UTF-8")
        }
}
