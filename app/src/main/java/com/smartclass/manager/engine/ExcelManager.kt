package com.smartclass.manager.engine

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.smartclass.manager.core.ErrorTracker
import com.smartclass.manager.data.EvaluationRecord
import com.smartclass.manager.data.Student
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

data class ImportPreview(
    val names: List<String>,
    val warnings: List<String>
)

object ExcelManager {

    /**
     * قراءة ملف CSV أو XLSX وتحديد الأسماء تلقائياً
     */
    fun importFromUri(context: Context, uri: Uri, fileName: String): ImportPreview {
        val lower = fileName.lowercase()
        return if (lower.endsWith(".xlsx")) {
            readXlsx(context, uri)
        } else {
            readCsvOrText(context, uri)
        }
    }

    private fun readCsvOrText(context: Context, uri: Uri): ImportPreview {
        val names = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
                lines.forEachIndexed { idx, rawLine ->
                    val lineNum = idx + 1
                    val cleanLine = rawLine.removePrefix("\uFEFF").trim()
                    if (cleanLine.isEmpty()) return@forEachIndexed

                    val delimiter = when {
                        cleanLine.contains(';') -> ';'
                        cleanLine.contains('\t') -> '\t'
                        else -> ','
                    }
                    val cols = cleanLine.split(delimiter).map { it.trim().removeSurrounding("\"") }
                    val extracted = extractNameFromRow(cols)

                    if (idx == 0 && isHeaderRow(extracted)) return@forEachIndexed
                    if (extracted.isEmpty()) {
                        warnings.add("السطر $lineNum لا يحتوي على اسم صالح")
                    } else {
                        names.add(extracted)
                    }
                }
            }
        }
        return ImportPreview(names, warnings)
    }

    private fun readXlsx(context: Context, uri: Uri): ImportPreview {
        val sharedStrings = mutableListOf<String>()
        val rows = mutableListOf<List<String>>()
        val warnings = mutableListOf<String>()

        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zis ->
                var sheetXmlBytes: ByteArray? = null
                var sharedXmlBytes: ByteArray? = null

                var entry = zis.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "xl/sharedStrings.xml" -> sharedXmlBytes = zis.readBytes()
                        "xl/worksheets/sheet1.xml" -> sheetXmlBytes = zis.readBytes()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }

                if (sharedXmlBytes != null) {
                    sharedStrings.addAll(parseSharedStrings(sharedXmlBytes))
                }
                if (sheetXmlBytes != null) {
                    rows.addAll(parseSheetRows(sheetXmlBytes, sharedStrings))
                } else {
                    warnings.add("لم يتم العثور على الورقة الأولى داخل ملف الإكسل")
                }
            }
        }

        val names = mutableListOf<String>()
        rows.forEachIndexed { idx, cols ->
            val extracted = extractNameFromRow(cols)
            if (idx == 0 && isHeaderRow(extracted)) return@forEachIndexed
            if (extracted.isEmpty()) {
                warnings.add("الصف ${idx + 1} فارغ أو بدون اسم")
            } else {
                names.add(extracted)
            }
        }
        return ImportPreview(names, warnings)
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val list = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")
        var insideSi = false
        val currentText = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "si") {
                        insideSi = true
                        currentText.clear()
                    }
                }
                XmlPullParser.TEXT -> {
                    if (insideSi) currentText.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "si") {
                        list.add(currentText.toString().trim())
                        insideSi = false
                    }
                }
            }
            event = parser.next()
        }
        return list
    }

    private fun parseSheetRows(bytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val result = mutableListOf<List<String>>()
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")

        var currentRow = mutableListOf<String>()
        var cellType: String? = null
        var insideValue = false
        val cellVal = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> currentRow = mutableListOf()
                    "c" -> cellType = parser.getAttributeValue(null, "t")
                    "v", "t" -> {
                        insideValue = true
                        cellVal.clear()
                    }
                }
                XmlPullParser.TEXT -> if (insideValue) cellVal.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> {
                        insideValue = false
                        val raw = cellVal.toString().trim()
                        val resolved = if (cellType == "s") {
                            val idx = raw.toIntOrNull()
                            if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else ""
                        } else raw
                        currentRow.add(resolved)
                    }
                    "row" -> if (currentRow.isNotEmpty()) result.add(currentRow)
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun extractNameFromRow(cols: List<String>): String {
        val nonEmpty = cols.filter { it.isNotBlank() && it.toDoubleOrNull() == null }
        return when {
            nonEmpty.isEmpty() -> ""
            nonEmpty.size == 1 -> nonEmpty[0]
            else -> "${nonEmpty[0]} ${nonEmpty[1]}".trim()
        }
    }

    private fun isHeaderRow(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("الاسم") || lower.contains("اللقب") ||
            lower.contains("name") || lower.contains("nom")
    }

    /**
     * تصدير سجل التقييمات أو كشف النقاط بصيغة CSV متوافقة مع Excel العربي
     */
    fun exportRecordsToCsv(context: Context, uri: Uri, records: List<EvaluationRecord>) {
        ErrorTracker.safeRun("ExcelManager", "exportRecordsToCsv") {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // UTF-8 BOM
                val sb = StringBuilder()
                sb.appendLine("التاريخ,القسم,التلميذ,الحالة,النقطة,التشجيع(+),المادة,الملاحظة")
                for (r in records) {
                    sb.appendLine(
                        "\"${r.dateStr}\",\"${r.className}\",\"${r.studentName}\",\"${r.status.arabicLabel}\",${r.score},${r.bonusAdded},\"${r.subject}\",\"${r.note}\""
                    )
                }
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
            }
        }
    }

    fun exportClassGradesToCsv(context: Context, uri: Uri, className: String, students: List<Student>) {
        ErrorTracker.safeRun("ExcelManager", "exportClassGradesToCsv") {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                val sb = StringBuilder()
                sb.appendLine("الرقم,اللقب والاسم,القسم,مجموع النقاط,نقاط التشجيع,مرات الإجابة,عدم الإجابة,الغياب")
                students.forEachIndexed { index, s ->
                    sb.appendLine(
                        "${index + 1},\"${s.fullName}\",\"$className\",${s.totalScore},${s.bonusCount},${s.answeredCount},${s.missedCount},${s.absentCount}"
                    )
                }
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
            }
        }
    }
}
