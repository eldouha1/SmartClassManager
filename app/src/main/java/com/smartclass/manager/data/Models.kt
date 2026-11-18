package com.smartclass.manager.data

import java.util.UUID

enum class EvaluationStatus(val arabicLabel: String) {
    ANSWERED("أجاب"),
    MISSED("لم يجب"),
    ABSENT("غائب")
}

data class Classroom(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class Student(
    val id: String = UUID.randomUUID().toString(),
    var classId: String,
    var fullName: String,
    var totalScore: Double = 0.0,
    var bonusCount: Int = 0,
    var answeredCount: Int = 0,
    var missedCount: Int = 0,
    var absentCount: Int = 0,
    var drawCount: Int = 0
)

data class EvaluationRecord(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val classId: String,
    var className: String,
    val studentId: String,
    var studentName: String,
    val status: EvaluationStatus,
    val score: Double = 0.0,
    val bonusAdded: Double = 0.0,
    val subject: String = "",
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val dateStr: String
)

data class ClassSession(
    val id: String = UUID.randomUUID().toString(),
    val classId: String,
    var title: String,
    val dateStr: String,
    val createdAt: Long = System.currentTimeMillis(),
    val remainingStudentIds: MutableList<String> = mutableListOf(),
    val absentStudentIds: MutableList<String> = mutableListOf(),
    var pickedCount: Int = 0,
    var isCompleted: Boolean = false
)

data class AppDatabase(
    val classes: MutableList<Classroom> = mutableListOf(),
    val students: MutableList<Student> = mutableListOf(),
    val records: MutableList<EvaluationRecord> = mutableListOf(),
    val sessions: MutableList<ClassSession> = mutableListOf(),
    var activeClassId: String? = null
)
