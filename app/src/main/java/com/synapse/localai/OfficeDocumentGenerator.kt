package com.synapse.localai

import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object OfficeDocumentGenerator {
    private val arabicCharacters = Regex("""[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\uFB50-\uFDFF\uFE70-\uFEFF]""")

    private fun isArabic(text: String): Boolean = arabicCharacters.containsMatchIn(text)

    fun createWordDocument(title: String, content: String): ByteArray = zip(
        mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/document.xml" to wordDocumentXml(title, content)
        )
    )

    fun createSpreadsheet(sheetName: String, rows: List<List<String>>): ByteArray {
        require(rows.isNotEmpty()) { "Spreadsheet data must contain at least one row." }
        require(rows.any { row -> row.any(String::isNotBlank) }) { "Spreadsheet data cannot be completely empty." }
        val sharedStrings = rows.flatten().distinct()
        val sharedStringIndexes = sharedStrings.withIndex().associate { (index, value) -> value to index }
        val safeSheetName = xml(
            sheetName.ifBlank { "Sheet1" }
                .replace(Regex("""[\\/:?*\[\]]"""), "_")
                .trim('\'')
                .ifBlank { "Sheet1" }
                .take(31)
        )
        return zip(
            mapOf(
                "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/></Types>""",
                "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
                "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><bookViews><workbookView/></bookViews><sheets><sheet name="$safeSheetName" sheetId="1" r:id="rId1"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/></Relationships>""",
                "xl/sharedStrings.xml" to sharedStringsXml(sharedStrings, rows.sumOf { it.size }),
                "xl/worksheets/sheet1.xml" to worksheetXml(rows, sharedStringIndexes)
            )
        )
    }

    fun spreadsheetPreview(rows: List<List<String>>, maxRows: Int = 20): String =
        rows.take(maxRows).joinToString("\n") { row -> row.joinToString("\t") }

    fun parseSpreadsheetRows(raw: String): List<List<String>> {
        val input = raw.trim()
        require(input.isNotBlank()) { "Spreadsheet data_json is required." }
        val rows = mutableListOf<List<String>>()
        if (input.startsWith("[")) {
            val array = JSONArray(input)
            require(array.length() > 0) { "Spreadsheet data must contain at least one row." }
            val first = array.opt(0)
            if (first is JSONObject) {
                val headers = first.keys().asSequence().toList().sorted()
                require(headers.isNotEmpty()) { "Spreadsheet row objects must contain fields." }
                rows.add(headers)
                for (index in 0 until array.length()) {
                    val row = array.optJSONObject(index)
                        ?: throw IllegalArgumentException("All spreadsheet rows must have the same JSON shape.")
                    rows.add(headers.map { key -> row.opt(key).spreadsheetCellText() })
                }
            } else {
                for (index in 0 until array.length()) {
                    val row = array.optJSONArray(index)
                        ?: throw IllegalArgumentException("Provide spreadsheet rows as a JSON array of arrays or objects.")
                    rows.add((0 until row.length()).map { cell -> row.opt(cell).spreadsheetCellText() })
                }
            }
        } else {
            val obj = JSONObject(input)
            val headers = (obj.optJSONArray("headers") ?: obj.optJSONArray("columns"))
                ?.let { array -> (0 until array.length()).map { array.opt(it).spreadsheetCellText() } }
            val dataRows = obj.optJSONArray("rows") ?: obj.optJSONArray("data") ?: obj.optJSONArray("values")
            if (dataRows == null) {
                val keys = obj.keys().asSequence().toList().sorted()
                require(keys.isNotEmpty()) { "Spreadsheet object must contain at least one field." }
                rows.add(keys)
                rows.add(keys.map { obj.opt(it).spreadsheetCellText() })
            } else {
                var objectHeaders = headers
                if (objectHeaders != null) rows.add(objectHeaders)
                for (index in 0 until dataRows.length()) {
                    val rowArray = dataRows.optJSONArray(index)
                    if (rowArray != null) {
                        rows.add((0 until rowArray.length()).map { rowArray.opt(it).spreadsheetCellText() })
                        continue
                    }
                    val rowObject = dataRows.optJSONObject(index)
                        ?: throw IllegalArgumentException("Each rows entry must be a JSON array or object.")
                    if (objectHeaders == null) {
                        objectHeaders = rowObject.keys().asSequence().toList().sorted()
                        require(objectHeaders.isNotEmpty()) { "Spreadsheet row objects must contain fields." }
                        rows.add(objectHeaders)
                    }
                    rows.add(objectHeaders.map { key -> rowObject.opt(key).spreadsheetCellText() })
                }
            }
        }
        require(rows.isNotEmpty() && rows.size <= 5000) { "Spreadsheet must contain between 1 and 5,000 rows." }
        require(rows.any { row -> row.any(String::isNotBlank) }) { "Spreadsheet data cannot be completely empty." }
        require(rows.maxOf { it.size } <= 100) { "Spreadsheet cannot exceed 100 columns." }
        require(rows.all { row -> row.all { it.length <= 10_000 } }) { "A spreadsheet cell exceeds the 10,000-character limit." }
        require(rows.sumOf { row -> row.sumOf { it.length.toLong() } } <= 2_000_000L) {
            "Spreadsheet data exceeds the 2 MB generation limit."
        }
        return rows
    }

    private fun Any?.spreadsheetCellText(): String = when (this) {
        null, JSONObject.NULL -> ""
        is JSONObject, is JSONArray -> toString()
        else -> toString()
    }

    private fun wordDocumentXml(title: String, content: String): String {
        val paragraphs = buildList {
            if (title.isNotBlank()) add(paragraph(title, bold = true, fontSizeHalfPoints = 20))
            content.split('\n').forEach { add(paragraph(it, fontSizeHalfPoints = 22)) }
        }.joinToString("")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$paragraphs<w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>"""
    }

    private fun paragraph(text: String, bold: Boolean = false, fontSizeHalfPoints: Int): String {
        val rtl = isArabic(text)
        val paragraphProperties = if (rtl) """<w:pPr><w:bidi/></w:pPr>""" else ""
        val runProperties = buildString {
            if (bold) append("<w:b/>")
            append("""<w:sz w:val="$fontSizeHalfPoints"/><w:szCs w:val="$fontSizeHalfPoints"/>""")
            if (rtl) append("""<w:rtl/><w:lang w:bidi="ar-SA"/>""")
        }.takeIf { it.isNotEmpty() }?.let { "<w:rPr>$it</w:rPr>" }.orEmpty()
        return """<w:p>$paragraphProperties<w:r>$runProperties<w:t xml:space="preserve">${xml(text)}</w:t></w:r></w:p>"""
    }

    private fun sharedStringsXml(strings: List<String>, cellCount: Int): String {
        val items = strings.joinToString("") { value ->
            """<si><t xml:space="preserve">${xml(value)}</t></si>"""
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="$cellCount" uniqueCount="${strings.size}">$items</sst>"""
    }

    private fun worksheetXml(rows: List<List<String>>, sharedStringIndexes: Map<String, Int>): String {
        val rightToLeft = rows.flatten().any(::isArabic)
        val sheetView = if (rightToLeft) {
            """<sheetViews><sheetView workbookViewId="0" rightToLeft="1"/></sheetViews>"""
        } else {
            """<sheetViews><sheetView workbookViewId="0"/></sheetViews>"""
        }
        val maxColumns = rows.maxOfOrNull { it.size } ?: 0
        val dimension = if (maxColumns > 0) {
            """<dimension ref="A1:${columnName(maxColumns)}${rows.size}"/>"""
        } else {
            """<dimension ref="A1"/>"""
        }
        val body = rows.mapIndexed { rowIndex, row ->
            val cells = row.mapIndexed { columnIndex, value ->
                val address = "${columnName(columnIndex + 1)}${rowIndex + 1}"
                val sharedStringIndex = sharedStringIndexes.getValue(value)
                """<c r="$address" t="s"><v>$sharedStringIndex</v></c>"""
            }.joinToString("")
            """<row r="${rowIndex + 1}">$cells</row>"""
        }.joinToString("")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">$dimension$sheetView<sheetData>$body</sheetData></worksheet>"""
    }

    private fun columnName(number: Int): String {
        var value = number
        val result = StringBuilder()
        while (value > 0) {
            val remainder = (value - 1) % 26
            result.insert(0, ('A'.code + remainder).toChar())
            value = (value - 1) / 26
        }
        return result.toString()
    }

    private fun xml(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when {
                char == '&' -> append("&amp;")
                char == '<' -> append("&lt;")
                char == '>' -> append("&gt;")
                char == '"' -> append("&quot;")
                char == '\'' -> append("&apos;")
                char == '\t' || char == '\n' || char == '\r' || char.code >= 0x20 -> append(char)
            }
        }
    }

    private fun zip(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output, Charsets.UTF_8).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
