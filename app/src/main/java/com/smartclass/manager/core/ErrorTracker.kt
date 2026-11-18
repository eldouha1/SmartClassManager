package com.smartclass.manager.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ErrorReport(
    val timestamp: String,
    val module: String,
    val actionName: String,
    val fileName: String,
    val methodName: String,
    val lineNumber: Int,
    val message: String,
    val breadcrumbs: List<String>,
    val fullStack: String
) {
    fun toCopyableText(): String {
        val steps = if (breadcrumbs.isEmpty()) "لا توجد خطوات مسجلة"
        else breadcrumbs.joinToString("\n  ⬅️ ")
        return """
            === تقرير خطأ تقني ===
            الوقت: $timestamp
            الوحدة: $module | العملية: $actionName
            الملف: $fileName
            الدالة: $methodName
            رقم السطر: $lineNumber
            الرسالة: $message
            آخر الخطوات:
              ⬅️ $steps
            ======================
        """.trimIndent()
    }
}

object ErrorTracker {

    private val breadcrumbs = ArrayDeque<String>()
    private val errorHistory = mutableListOf<ErrorReport>()
    private var appContext: Context? = null

    /**
     * تهيئة صائد الأعطال غير المتوقعة عند تشغيل التطبيق
     */
    fun initCrashHandler(context: Context) {
        appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val report = buildReport("AppCrash", "UncaughtException", throwable)
            saveLastCrash(report)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * تسجيل خطوة قام بها المستخدم (لتتبع سبب الخطأ)
     */
    fun addBreadcrumb(step: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        if (breadcrumbs.size >= AppConfig.MAX_BREADCRUMBS) {
            breadcrumbs.removeFirst()
        }
        breadcrumbs.addLast("[$time] $step")
    }

    /**
     * غلاف الحماية للدوال: يمنع توقف التطبيق ويحدد مكان الخطأ بدقة
     */
    inline fun safeRun(
        module: String,
        actionName: String,
        noinline onError: ((ErrorReport) -> Unit)? = null,
        block: () -> Unit
    ) {
        try {
            block()
        } catch (t: Throwable) {
            val report = recordError(module, actionName, t)
            onError?.invoke(report)
        }
    }

    fun recordError(module: String, actionName: String, throwable: Throwable): ErrorReport {
        val report = buildReport(module, actionName, throwable)
        errorHistory.add(0, report)
        if (errorHistory.size > 50) errorHistory.removeLast()
        return report
    }

    private fun buildReport(module: String, actionName: String, throwable: Throwable): ErrorReport {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val appFrame = throwable.stackTrace.firstOrNull {
            it.className.startsWith("com.smartclass.manager")
        } ?: throwable.stackTrace.firstOrNull()

        return ErrorReport(
            timestamp = time,
            module = module,
            actionName = actionName,
            fileName = appFrame?.fileName ?: "غير معروف",
            methodName = appFrame?.methodName ?: "غير معروف",
            lineNumber = appFrame?.lineNumber ?: -1,
            message = throwable.localizedMessage ?: throwable.javaClass.simpleName,
            breadcrumbs = breadcrumbs.toList(),
            fullStack = throwable.stackTraceToString().take(1500)
        )
    }

    fun getHistory(): List<ErrorReport> = errorHistory.toList()
    fun getBreadcrumbs(): List<String> = breadcrumbs.toList()
    fun clearHistory() { errorHistory.clear() }

    private fun saveLastCrash(report: ErrorReport) {
        appContext?.getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(AppConfig.KEY_CRASH_LOG, report.toCopyableText())
            ?.commit()
    }

    fun consumeLastCrash(context: Context): String? {
        val prefs = context.getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val log = prefs.getString(AppConfig.KEY_CRASH_LOG, null)
        if (log != null) {
            prefs.edit().remove(AppConfig.KEY_CRASH_LOG).apply()
        }
        return log
    }
}
