package com.zzupocket.app.data.profile

import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.domain.StudentProfile
import org.json.JSONObject

/** 学籍信息的本地缓存（加密 SharedPreferences，含身份证号） */
object ProfileStore {

    fun save(profile: StudentProfile) {
        val json = JSONObject().apply {
            put("name", profile.name)
            put("studentId", profile.studentId)
            put("gender", profile.gender)
            put("idCard", profile.idCard)
            put("examNo", profile.examNo)
            put("enrollDate", profile.enrollDate)
            put("major", profile.major)
            put("duration", profile.duration)
            put("level", profile.level)
            put("department", profile.department)
            put("className", profile.className)
            put("sourceSchool", profile.sourceSchool)
        }.toString()
        AppGraph.tokenStore.profileJson = json
    }

    fun load(): StudentProfile? {
        val raw = AppGraph.tokenStore.profileJson ?: return null
        return runCatching {
            val o = JSONObject(raw)
            StudentProfile(
                name = o.optString("name"),
                studentId = o.optString("studentId"),
                gender = o.optString("gender"),
                idCard = o.optString("idCard"),
                examNo = o.optString("examNo"),
                enrollDate = o.optString("enrollDate"),
                major = o.optString("major"),
                duration = o.optString("duration"),
                level = o.optString("level"),
                department = o.optString("department"),
                className = o.optString("className"),
                sourceSchool = o.optString("sourceSchool"),
            )
        }.getOrNull()
    }
}
