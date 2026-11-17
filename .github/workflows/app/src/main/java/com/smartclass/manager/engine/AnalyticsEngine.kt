package com.smartclass.manager.engine

import com.smartclass.manager.data.EvaluationRecord
import com.smartclass.manager.data.EvaluationStatus
import com.smartclass.manager.data.Student

data class ClassAnalytics(
    val totalStudents: Int,
    val participatedStudents: Int,
    val coveragePercent: Int,
    val leastParticipated: List<Student>,
    val topPerformers: List<Student>,
    val totalAnswered: Int,
    val totalMissed: Int,
    val totalAbsent: Int,
    val minDraws: Int,
    val maxDraws: Int
)

object AnalyticsEngine {

    fun compute(students: List<Student>, classRecords: List<EvaluationRecord>): ClassAnalytics {
        val total = students.size
        val participated = students.count { (it.answeredCount + it.missedCount) > 0 }
        val coverage = if (total == 0) 0 else (participated * 100) / total

        val least = students
            .sortedWith(compareBy<Student> { it.answeredCount + it.missedCount }.thenBy { it.drawCount })
            .take(5)

        val top = students
            .sortedByDescending { it.totalScore }
            .take(5)

        val minDraws = students.minOfOrNull { it.drawCount } ?: 0
        val maxDraws = students.maxOfOrNull { it.drawCount } ?: 0

        return ClassAnalytics(
            totalStudents = total,
            participatedStudents = participated,
            coveragePercent = coverage,
            leastParticipated = least,
            topPerformers = top,
            totalAnswered = classRecords.count { it.status == EvaluationStatus.ANSWERED },
            totalMissed = classRecords.count { it.status == EvaluationStatus.MISSED },
            totalAbsent = classRecords.count { it.status == EvaluationStatus.ABSENT },
            minDraws = minDraws,
            maxDraws = maxDraws
        )
    }
}
