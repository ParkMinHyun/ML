package com.samsung.android.camera.core2.ml

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal row-streaming XLSX writer for Android.
 *
 * Apache POI's XSSF model retains the complete workbook and shared-string XML in the Java heap. SXSSF avoids that
 * cost, but its sheet constructor reaches java.awt font classes that Android does not provide. This writer emits the
 * small OOXML subset used by the metrics exporter directly into the ZIP container: rows are discarded immediately,
 * and strings are stored inline instead of in a process-wide shared-string table.
 */
internal class StreamingXlsxWorkbook(outputFile: File) : Closeable {
    private val output = ZipOutputStream(FileOutputStream(outputFile))
    private val sheetNames = mutableListOf<String>()
    private var closed = false

    fun <T> writeSheet(
        requestedName: String,
        columnTitles: List<String>,
        rows: Sequence<T>,
        values: (T) -> List<Any?>,
    ) {
        check(!closed) { "Workbook is closed" }

        val sheetName = uniqueSheetName(requestedName)
        val sheetIndex = sheetNames.size + 1
        sheetNames.add(sheetName)

        output.putNextEntry(ZipEntry("xl/worksheets/sheet$sheetIndex.xml"))
        val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
        writer.write(XML_DECLARATION)
        writer.write("<worksheet xmlns=\"$SPREADSHEET_NAMESPACE\"><sheetData>")
        writeRow(writer, 1, columnTitles, columnTitles)

        var rowIndex = 2
        rows.forEach { item ->
            writeRow(writer, rowIndex, columnTitles, values(item))
            rowIndex++
        }

        writer.write("</sheetData></worksheet>")
        writer.flush()
        output.closeEntry()
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true

        try {
            writeContentTypes()
            writeRootRelationships()
            writeWorkbook()
            writeWorkbookRelationships()
            writeStyles()
        } finally {
            output.close()
        }
    }

    private fun writeRow(
        writer: BufferedWriter,
        rowIndex: Int,
        columnTitles: List<String>,
        values: List<Any?>,
    ) {
        writer.write("<row r=\"")
        writer.write(rowIndex.toString())
        writer.write("\">")

        columnTitles.indices.forEach { columnIndex ->
            val value = values.getOrNull(columnIndex)
            if (value == null || value == "") {
                return@forEach
            }
            val cellReference = columnName(columnIndex) + rowIndex
            val styleIndex = styleIndexFor(columnTitles[columnIndex], value)
            writeCell(writer, cellReference, styleIndex, value)
        }

        writer.write("</row>")
    }

    private fun writeCell(
        writer: BufferedWriter,
        reference: String,
        styleIndex: Int,
        value: Any,
    ) {
        writer.write("<c r=\"")
        writer.write(reference)
        writer.write('"'.code)
        if (styleIndex != DEFAULT_STYLE_INDEX) {
            writer.write(" s=\"")
            writer.write(styleIndex.toString())
            writer.write('"'.code)
        }

        when (value) {
            is Boolean -> {
                writer.write(" t=\"b\"><v>")
                writer.write(if (value) "1" else "0")
                writer.write("</v></c>")
            }

            is Number -> {
                val number = value.toDouble()
                if (number.isFinite()) {
                    writer.write("><v>")
                    writer.write(number.toString())
                    writer.write("</v></c>")
                } else {
                    writeInlineStringCellBody(writer, value.toString())
                }
            }

            else -> writeInlineStringCellBody(writer, value.toString())
        }
    }

    private fun writeInlineStringCellBody(writer: BufferedWriter, rawValue: String) {
        writer.write(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
        writer.write(escapeXmlText(rawValue.take(MAX_CELL_TEXT_LENGTH)))
        writer.write("</t></is></c>")
    }

    private fun writeContentTypes() {
        writeEntry("[Content_Types].xml") { writer ->
            writer.write(XML_DECLARATION)
            writer.write("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            writer.write("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            writer.write("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            writer.write("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
            writer.write("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
            sheetNames.indices.forEach { index ->
                writer.write("<Override PartName=\"/xl/worksheets/sheet${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            }
            writer.write("</Types>")
        }
    }

    private fun writeRootRelationships() {
        writeEntry("_rels/.rels") { writer ->
            writer.write(XML_DECLARATION)
            writer.write("<Relationships xmlns=\"$PACKAGE_RELATIONSHIP_NAMESPACE\">")
            writer.write("<Relationship Id=\"rId1\" Type=\"$OFFICE_DOCUMENT_RELATIONSHIP\" Target=\"xl/workbook.xml\"/>")
            writer.write("</Relationships>")
        }
    }

    private fun writeWorkbook() {
        writeEntry("xl/workbook.xml") { writer ->
            writer.write(XML_DECLARATION)
            writer.write("<workbook xmlns=\"$SPREADSHEET_NAMESPACE\" xmlns:r=\"$OFFICE_RELATIONSHIP_NAMESPACE\"><sheets>")
            sheetNames.forEachIndexed { index, sheetName ->
                writer.write("<sheet name=\"")
                writer.write(escapeXmlAttribute(sheetName))
                writer.write("\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>")
            }
            writer.write("</sheets></workbook>")
        }
    }

    private fun writeWorkbookRelationships() {
        writeEntry("xl/_rels/workbook.xml.rels") { writer ->
            writer.write(XML_DECLARATION)
            writer.write("<Relationships xmlns=\"$PACKAGE_RELATIONSHIP_NAMESPACE\">")
            sheetNames.indices.forEach { index ->
                writer.write("<Relationship Id=\"rId${index + 1}\" Type=\"$WORKSHEET_RELATIONSHIP\" Target=\"worksheets/sheet${index + 1}.xml\"/>")
            }
            writer.write("<Relationship Id=\"rId${sheetNames.size + 1}\" Type=\"$STYLES_RELATIONSHIP\" Target=\"styles.xml\"/>")
            writer.write("</Relationships>")
        }
    }

    private fun writeStyles() {
        writeEntry("xl/styles.xml") { writer ->
            writer.write(XML_DECLARATION)
            writer.write(
                "<styleSheet xmlns=\"$SPREADSHEET_NAMESPACE\">" +
                    "<numFmts count=\"3\">" +
                    "<numFmt numFmtId=\"164\" formatCode=\"0&quot; ms&quot;\"/>" +
                    "<numFmt numFmtId=\"165\" formatCode=\"0.0&quot;%&quot;\"/>" +
                    "<numFmt numFmtId=\"166\" formatCode=\"0.0%\"/>" +
                    "</numFmts>" +
                    "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/><scheme val=\"minor\"/></font></fonts>" +
                    "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
                    "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
                    "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                    "<cellXfs count=\"4\">" +
                    "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
                    "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
                    "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
                    "<xf numFmtId=\"166\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
                    "</cellXfs>" +
                    "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
                    "</styleSheet>",
            )
        }
    }

    private fun writeEntry(name: String, content: (BufferedWriter) -> Unit) {
        output.putNextEntry(ZipEntry(name))
        val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
        content(writer)
        writer.flush()
        output.closeEntry()
    }

    private fun uniqueSheetName(rawName: String): String {
        val base = rawName.replace(INVALID_SHEET_NAME_CHARACTERS, "_")
            .take(MAX_SHEET_NAME_LENGTH)
            .ifEmpty { "Sheet" }
        if (base !in sheetNames) {
            return base
        }

        var suffix = 2
        while (true) {
            val suffixText = "_$suffix"
            val candidate = base.take(MAX_SHEET_NAME_LENGTH - suffixText.length) + suffixText
            if (candidate !in sheetNames) {
                return candidate
            }
            suffix++
        }
    }

    private companion object {
        private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        private const val SPREADSHEET_NAMESPACE = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        private const val OFFICE_RELATIONSHIP_NAMESPACE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val PACKAGE_RELATIONSHIP_NAMESPACE = "http://schemas.openxmlformats.org/package/2006/relationships"
        private const val OFFICE_DOCUMENT_RELATIONSHIP = "$OFFICE_RELATIONSHIP_NAMESPACE/officeDocument"
        private const val WORKSHEET_RELATIONSHIP = "$OFFICE_RELATIONSHIP_NAMESPACE/worksheet"
        private const val STYLES_RELATIONSHIP = "$OFFICE_RELATIONSHIP_NAMESPACE/styles"
        private const val MAX_SHEET_NAME_LENGTH = 31
        private const val MAX_CELL_TEXT_LENGTH = 32767
        private const val DEFAULT_STYLE_INDEX = 0
        private const val MILLISECONDS_STYLE_INDEX = 1
        private const val PERCENT_STYLE_INDEX = 2
        private const val RATE_STYLE_INDEX = 3
        private val INVALID_SHEET_NAME_CHARACTERS = Regex("[:\\\\/?*\\[\\]]")

        private fun styleIndexFor(columnTitle: String, value: Any): Int {
            if (value !is Number) {
                return DEFAULT_STYLE_INDEX
            }
            return when {
                columnTitle.endsWith("Ms", ignoreCase = true) -> MILLISECONDS_STYLE_INDEX
                columnTitle.endsWith("Percent", ignoreCase = true) -> PERCENT_STYLE_INDEX
                columnTitle.endsWith("Rate", ignoreCase = true) -> RATE_STYLE_INDEX
                else -> DEFAULT_STYLE_INDEX
            }
        }

        private fun columnName(index: Int): String {
            var value = index + 1
            val result = StringBuilder()
            while (value > 0) {
                val remainder = (value - 1) % 26
                result.append(('A'.code + remainder).toChar())
                value = (value - 1) / 26
            }
            return result.reverse().toString()
        }

        private fun escapeXmlText(value: String): String = buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '\t', '\n', '\r' -> append(character)
                    else -> if (character.code >= 0x20) append(character)
                }
            }
        }

        private fun escapeXmlAttribute(value: String): String = escapeXmlText(value)
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
