package com.zzupocket.app.domain

/** 学籍信息（来自教务「学籍档案 → 基本信息」） */
data class StudentProfile(
    val name: String = "",
    val studentId: String = "",
    val gender: String = "",
    val idCard: String = "",
    val examNo: String = "",
    val enrollDate: String = "",
    val major: String = "",
    val duration: String = "",
    val level: String = "",
    val department: String = "",
    val className: String = "",
    val sourceSchool: String = "",
) {
    fun isBlank(): Boolean = listOf(
        name, studentId, gender, idCard, examNo, enrollDate,
        major, duration, level, department, className, sourceSchool,
    ).all { it.isBlank() }
}

/** 身份证号脱敏：保留前 6 位和后 4 位 */
fun maskIdCard(value: String): String {
    if (value.length <= 10) return "*".repeat(value.length)
    return value.take(6) + "*".repeat(value.length - 10) + value.takeLast(4)
}

/** 考生号脱敏：保留前 4 位和后 4 位 */
fun maskExamNo(value: String): String {
    if (value.length <= 8) return "*".repeat(value.length)
    return value.take(4) + "*".repeat(value.length - 8) + value.takeLast(4)
}
