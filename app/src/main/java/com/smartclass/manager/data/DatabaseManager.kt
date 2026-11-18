package com.smartclass.manager.data

import android.content.Context
import com.google.gson.Gson
import com.smartclass.manager.core.AppConfig
import com.smartclass.manager.core.AppSettings
import com.smartclass.manager.core.ErrorTracker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DatabaseManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    var db: AppDatabase = loadDatabase()
        private set

    var settings: AppSettings = loadSettings()
        private set

    private fun loadDatabase(): AppDatabase {
        val json = prefs.getString(AppConfig.KEY_DB_DATA, null) ?: return AppDatabase()
        return try {
            gson.fromJson(json, AppDatabase::class.java) ?: AppDatabase()
        } catch (t: Throwable) {
            ErrorTracker.recordError("DatabaseManager", "loadDatabase", t)
            AppDatabase()
        }
    }

    private fun loadSettings(): AppSettings {
        val json = prefs.getString(AppConfig.KEY_SETTINGS, null) ?: return AppSettings()
        return try {
            gson.fromJson(json, AppSettings::class.java) ?: AppSettings()
        } catch (t: Throwable) {
            ErrorTracker.recordError("DatabaseManager", "loadSettings", t)
            AppSettings()
        }
    }

    fun saveDatabase() {
        ErrorTracker.safeRun("DatabaseManager", "saveDatabase") {
            prefs.edit().putString(AppConfig.KEY_DB_DATA, gson.toJson(db)).apply()
        }
    }

    fun saveSettings() {
        ErrorTracker.safeRun("DatabaseManager", "saveSettings") {
            prefs.edit().putString(AppConfig.KEY_SETTINGS, gson.toJson(settings)).apply()
        }
    }

    // ==================== 1. عمليات الأقسام (Classes CRUD) ====================

    fun addClass(rawName: String): Result<Classroom> {
        val name = rawName.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("اسم القسم لا يمكن أن يكون فارغاً"))
        if (db.classes.any { it.name.equals(name, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("يوجد قسم بهذا الاسم مسبقاً: $name"))
        }
        val newClass = Classroom(name = name)
        db.classes.add(newClass)
        if (db.activeClassId == null) db.activeClassId = newClass.id
        saveDatabase()
        ErrorTracker.addBreadcrumb("إضافة قسم: $name")
        return Result.success(newClass)
    }

    fun renameClass(classId: String, rawNewName: String): Result<Unit> {
        val newName = rawNewName.trim()
        if (newName.isEmpty()) return Result.failure(IllegalArgumentException("الاسم الجديد فارغ"))
        val target = db.classes.find { it.id == classId }
            ?: return Result.failure(IllegalStateException("القسم غير موجود"))
        if (db.classes.any { it.id != classId && it.name.equals(newName, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("الاسم مستخدم لقسم آخر"))
        }
        target.name = newName
        db.records.filter { it.classId == classId }.forEach { it.className = newName }
        saveDatabase()
        ErrorTracker.addBreadcrumb("تعديل اسم قسم إلى: $newName")
        return Result.success(Unit)
    }

    fun deleteClass(classId: String) {
        db.classes.removeAll { it.id == classId }
        db.students.removeAll { it.classId == classId }
        db.sessions.removeAll { it.classId == classId }
        if (db.activeClassId == classId) {
            db.activeClassId = db.classes.firstOrNull()?.id
        }
        saveDatabase()
        ErrorTracker.addBreadcrumb("حذف قسم")
    }

    // ==================== 2. عمليات التلاميذ (Students CRUD) ====================

    fun getStudentsByClass(classId: String): List<Student> =
        db.students.filter { it.classId == classId }

    fun searchStudents(classId: String, query: String): List<Student> {
        val q = query.trim()
        val list = getStudentsByClass(classId)
        return if (q.isEmpty()) list else list.filter { it.fullName.contains(q, ignoreCase = true) }
    }

    fun addStudent(classId: String, rawName: String): Result<Student> {
        val name = rawName.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("اسم التلميذ فارغ"))
        if (db.students.any { it.classId == classId && it.fullName.equals(name, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("التلميذ مسجل مسبقاً في هذا القسم"))
        }
        val student = Student(classId = classId, fullName = name)
        db.students.add(student)
        // إضافته تلقائياً إلى الدورة الحالية غير المكتملة إن وجدت
        db.sessions.lastOrNull { it.classId == classId && !it.isCompleted }
            ?.remainingStudentIds?.add(student.id)
        saveDatabase()
        return Result.success(student)
    }

    fun addStudentsBulk(classId: String, names: List<String>): Pair<Int, Int> {
        var added = 0
        var skipped = 0
        val activeSession = db.sessions.lastOrNull { it.classId == classId && !it.isCompleted }
        for (raw in names) {
            val clean = raw.trim()
            if (clean.isEmpty()) continue
            val exists = db.students.any {
                it.classId == classId && it.fullName.equals(clean, ignoreCase = true)
            }
            if (exists) {
                skipped++
            } else {
                val student = Student(classId = classId, fullName = clean)
                db.students.add(student)
                activeSession?.remainingStudentIds?.add(student.id)
                added++
            }
        }
        saveDatabase()
        ErrorTracker.addBreadcrumb("إضافة جماعية: $added تلميذ (تجاهل $skipped)")
        return Pair(added, skipped)
    }

    fun updateStudent(studentId: String, rawNewName: String): Result<Unit> {
        val newName = rawNewName.trim()
        if (newName.isEmpty()) return Result.failure(IllegalArgumentException("الاسم الجديد فارغ"))
        val student = db.students.find { it.id == studentId }
            ?: return Result.failure(IllegalStateException("التلميذ غير موجود"))
        student.fullName = newName
        db.records.filter { it.studentId == studentId }.forEach { it.studentName = newName }
        saveDatabase()
        return Result.success(Unit)
    }

    fun deleteStudent(studentId: String) {
        db.students.removeAll { it.id == studentId }
        db.sessions.forEach {
            it.remainingStudentIds.remove(studentId)
            it.absentStudentIds.remove(studentId)
        }
        saveDatabase()
        ErrorTracker.addBreadcrumb("حذف تلميذ")
    }

    // ==================== 3. الحصص والسجل والتراجع (Sessions & Undo) ====================

    fun getLastUnfinishedSession(classId: String): ClassSession? =
        db.sessions.lastOrNull { it.classId == classId && !it.isCompleted }

    fun createNewSession(classId: String, customTitle: String? = null): ClassSession {
        // إغلاق أي حصة سابقة مفتوحة لنفس القسم
        db.sessions.filter { it.classId == classId && !it.isCompleted }
            .forEach { it.isCompleted = true }

        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val studentIds = getStudentsByClass(classId).map { it.id }.toMutableList()
        val session = ClassSession(
            classId = classId,
            title = customTitle ?: "حصة $dateStr",
            dateStr = dateStr,
            remainingStudentIds = studentIds
        )
        db.sessions.add(session)
        saveDatabase()
        ErrorTracker.addBreadcrumb("بدء حصة جديدة: ${session.title}")
        return session
    }

    fun recordEvaluation(
        sessionId: String,
        student: Student,
        status: EvaluationStatus,
        score: Double,
        bonus: Double,
        subject: String,
        note: String
    ): EvaluationRecord {
        val clsName = db.classes.find { it.id == student.classId }?.name ?: ""
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

        val record = EvaluationRecord(
            sessionId = sessionId,
            classId = student.classId,
            className = clsName,
            studentId = student.id,
            studentName = student.fullName,
            status = status,
            score = if (status == EvaluationStatus.ANSWERED) score else 0.0,
            bonusAdded = if (status == EvaluationStatus.ANSWERED) bonus else 0.0,
            subject = subject.trim(),
            note = note.trim(),
            dateStr = dateStr
        )
        db.records.add(0, record)

        // تحديث إحصائيات التلميذ
        student.drawCount++
        when (status) {
            EvaluationStatus.ANSWERED -> {
                student.answeredCount++
                student.totalScore += (score + bonus)
                if (bonus > 0) student.bonusCount++
            }
            EvaluationStatus.MISSED -> student.missedCount++
            EvaluationStatus.ABSENT -> student.absentCount++
        }

        // تحديث الحصة (استبعاد من الصندوق إذا كان منع التكرار مفعلاً أو كان غائباً)
        val session = db.sessions.find { it.id == sessionId }
        if (session != null) {
            if (status == EvaluationStatus.ABSENT) {
                session.absentStudentIds.add(student.id)
                session.remainingStudentIds.remove(student.id)
            } else {
                session.pickedCount++
                if (!settings.allowRepeatInCycle) {
                    session.remainingStudentIds.remove(student.id)
                }
            }
            if (session.remainingStudentIds.isEmpty()) {
                session.isCompleted = true
            }
        }

        saveDatabase()
        ErrorTracker.addBreadcrumb("رصد (${status.arabicLabel}) للتلميذ: ${student.fullName}")
        return record
    }

    fun undoRecord(recordId: String): Boolean {
        val record = db.records.find { it.id == recordId } ?: return false
        db.records.remove(record)

        val student = db.students.find { it.id == record.studentId }
        if (student != null) {
            if (student.drawCount > 0) student.drawCount--
            when (record.status) {
                EvaluationStatus.ANSWERED -> {
                    if (student.answeredCount > 0) student.answeredCount--
                    student.totalScore = (student.totalScore - (record.score + record.bonusAdded)).coerceAtLeast(0.0)
                    if (record.bonusAdded > 0 && student.bonusCount > 0) student.bonusCount--
                }
                EvaluationStatus.MISSED -> if (student.missedCount > 0) student.missedCount--
                EvaluationStatus.ABSENT -> if (student.absentCount > 0) student.absentCount--
            }
        }

        val session = db.sessions.find { it.id == record.sessionId }
        if (session != null && student != null) {
            session.absentStudentIds.remove(student.id)
            if (!session.remainingStudentIds.contains(student.id)) {
                session.remainingStudentIds.add(student.id)
            }
            if (record.status != EvaluationStatus.ABSENT && session.pickedCount > 0) {
                session.pickedCount--
            }
            session.isCompleted = false
        }

        saveDatabase()
        ErrorTracker.addBreadcrumb("تراجع فوري عن رصد: ${record.studentName}")
        return true
    }

    // ==================== 4. النسخ الاحتياطي والبيانات التجريبية ====================

    fun exportBackupJson(): String = gson.toJson(db)

    fun importBackupJson(json: String): Result<Unit> {
        return try {
            val parsed = gson.fromJson(json, AppDatabase::class.java)
                ?: return Result.failure(IllegalArgumentException("ملف النسخة الاحتياطية غير صالح"))
            db = parsed
            saveDatabase()
            ErrorTracker.addBreadcrumb("استرجاع نسخة احتياطية")
            Result.success(Unit)
        } catch (t: Throwable) {
            ErrorTracker.recordError("DatabaseManager", "importBackupJson", t)
            Result.failure(t)
        }
    }

    fun seedDemoClass(): Classroom {
        val demoName = "الأولى ثانوي علوم (تجريبي)"
        val existing = db.classes.find { it.name == demoName }
        if (existing != null) return existing

        val cls = addClass(demoName).getOrThrow()
        val sampleNames = listOf(
            "أمين بن يوسف", "سارة منصوري", "ياسين بوعلام", "مريم قاسمي", "عمر بلحاج",
            "خديجة زروقي", "إسلام مرابط", "نور الهدى شريف", "آدم حاجي", "لينة بركات"
        )
        addStudentsBulk(cls.id, sampleNames)
        db.activeClassId = cls.id
        saveDatabase()
        return cls
    }

    fun runHealthCheck(): String {
        val validClassIds = db.classes.map { it.id }.toSet()
        val orphanStudents = db.students.count { it.classId !in validClassIds }
        if (orphanStudents > 0) {
            db.students.removeAll { it.classId !in validClassIds }
            saveDatabase()
        }
        return "الأقسام: ${db.classes.size} | التلاميذ: ${db.students.size} | السجلات: ${db.records.size} (تم إصلاح $orphanStudents)"
    }
}
