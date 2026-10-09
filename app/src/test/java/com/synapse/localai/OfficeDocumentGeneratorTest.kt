package com.synapse.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class OfficeDocumentGeneratorTest {
    @Test
    fun wordDocumentContainsEscapedTextAndRequiredParts() {
        val entries = unzip(OfficeDocumentGenerator.createWordDocument("Atlas <Report>", "Hello & welcome"))

        assertTrue(entries.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml")))
        val xml = entries.getValue("word/document.xml")
        assertTrue(xml.contains("Atlas &lt;Report&gt;"))
        assertTrue(xml.contains("Hello &amp; welcome"))
        assertTrue(xml.contains("""<w:b/><w:sz w:val="20"/><w:szCs w:val="20"/>"""))
        assertTrue(xml.contains("""<w:sz w:val="22"/><w:szCs w:val="22"/>"""))
    }

    @Test
    fun wordDocumentMarksArabicParagraphsAsRightToLeft() {
        val entries = unzip(OfficeDocumentGenerator.createWordDocument("تقرير", "النتائج بالعربية\nEnglish paragraph"))
        val xml = entries.getValue("word/document.xml")

        assertTrue(xml.contains("<w:pPr><w:bidi/></w:pPr>"))
        assertTrue(xml.contains("""<w:rtl/><w:lang w:bidi="ar-SA"/>"""))
        assertTrue(xml.contains("English paragraph"))
    }

    @Test
    fun spreadsheetContainsCellsAndColumnAddresses() {
        val rows = listOf(listOf("Name", "Score"), listOf("Atlas", "10"))
        val entries = unzip(OfficeDocumentGenerator.createSpreadsheet("Scores", rows))
        val sheet = entries.getValue("xl/worksheets/sheet1.xml")
        val sharedStrings = entries.getValue("xl/sharedStrings.xml")

        assertTrue(entries.keys.containsAll(listOf(
            "[Content_Types].xml",
            "_rels/.rels",
            "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels",
            "xl/sharedStrings.xml",
            "xl/worksheets/sheet1.xml"
        )))
        assertTrue(entries.getValue("xl/workbook.xml").contains("<bookViews><workbookView/></bookViews>"))
        assertTrue(sheet.contains("""<dimension ref="A1:B2"/>"""))
        assertTrue(sheet.contains("""<c r="B2" t="s"><v>3</v></c>"""))
        assertTrue(sharedStrings.contains("<t xml:space=\"preserve\">Atlas</t>"))
        assertTrue(sharedStrings.contains("""count="4" uniqueCount="4""""))
        assertTrue(entries.getValue("xl/_rels/workbook.xml.rels").contains("sharedStrings.xml"))
        assertTrue(sheet.contains("""<sheetView workbookViewId="0"/>"""))
        assertEquals("Name\tScore\nAtlas\t10", OfficeDocumentGenerator.spreadsheetPreview(rows))
    }

    @Test
    fun spreadsheetDeduplicatesSharedStringsWithoutLosingCellValues() {
        val entries = unzip(
            OfficeDocumentGenerator.createSpreadsheet(
                "Repeated",
                listOf(listOf("Name", "Name"), listOf("Atlas", "Atlas"))
            )
        )
        val sheet = entries.getValue("xl/worksheets/sheet1.xml")
        val sharedStrings = entries.getValue("xl/sharedStrings.xml")

        assertTrue(sheet.contains("""<c r="A1" t="s"><v>0</v></c>"""))
        assertTrue(sheet.contains("""<c r="B1" t="s"><v>0</v></c>"""))
        assertTrue(sheet.contains("""<c r="A2" t="s"><v>1</v></c>"""))
        assertTrue(sheet.contains("""<c r="B2" t="s"><v>1</v></c>"""))
        assertTrue(sharedStrings.contains("""count="4" uniqueCount="2""""))
    }

    @Test
    fun arabicSpreadsheetUsesRightToLeftSheetLayout() {
        val entries = unzip(OfficeDocumentGenerator.createSpreadsheet("بيانات", listOf(listOf("الاسم", "القيمة"))))

        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("rightToLeft=\"1\""))
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return entries
    }
}
