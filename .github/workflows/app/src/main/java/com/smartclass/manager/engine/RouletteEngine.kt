package com.smartclass.manager.engine

import com.smartclass.manager.data.ClassSession
import com.smartclass.manager.data.Student
import java.security.SecureRandom

object RouletteEngine {

    private val secureRandom = SecureRandom()

    /**
     * خوارزمية فيشر-ياتس (Fisher-Yates) مع التشفير العتادي لضمان عشوائية حقيقية
     */
    fun <T> shuffleCrypto(items: List<T>): List<T> {
        val list = items.toMutableList()
        for (i in list.size - 1 downTo 1) {
            val j = secureRandom.nextInt(i + 1)
            val temp = list[i]
            list[i] = list[j]
            list[j] = temp
        }
        return list
    }

    /**
     * اختيار تلميذ عشوائي من الصندوق المتاح في الحصة
     */
    fun pickNextStudent(
        allStudents: List<Student>,
        session: ClassSession,
        allowRepeat: Boolean
    ): Student? {
        val pool = if (allowRepeat) {
            allStudents.filter { it.id !in session.absentStudentIds }
        } else {
            allStudents.filter { it.id in session.remainingStudentIds }
        }
        if (pool.isEmpty()) return null
        val shuffled = shuffleCrypto(pool)
        return shuffled[secureRandom.nextInt(shuffled.size)]
    }

    /**
     * تقسيم القسم عشوائياً وبشكل متوازن إلى مجموعات عمل
     */
    fun generateRandomGroups(students: List<Student>, groupSize: Int): List<List<Student>> {
        if (students.isEmpty() || groupSize <= 0) return emptyList()
        val shuffled = shuffleCrypto(students)
        return shuffled.chunked(groupSize)
    }
}
