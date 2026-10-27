package com.zzupocket.app.data.qingguo

import com.zzupocket.app.core.school.SchoolProfile
import com.zzupocket.app.domain.StudentProfile
import kotlinx.serialization.json.Json

/**
 * 学籍信息：在已登录的 WebView 里打开「学籍档案 → 基本信息」，
 * 等表格渲染完，把页面里所有 单元格 文本抓出来，再按「标签: 值」配对。
 */
object QingguoProfileScraper {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    suspend fun fetch(): StudentProfile? {
        val raw = QingguoWebSession.runScript(SchoolProfile.profilePath, SCRIPT, timeoutMs = 30_000)
            ?: return null
        val cells = runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        android.util.Log.i("QingguoProfile", "cells=${cells.size}")
        if (cells.isEmpty()) return null
        return parse(cells)
    }

    private fun parse(cells: List<String>): StudentProfile {
        var p = StudentProfile()
        var i = 0
        while (i < cells.size - 1) {
            val label = cells[i].replace(Regex("""\s+"""), "")
            val value = cells[i + 1].trim()
            if (value.isNotEmpty()) {
                when {
                    label == "学号" -> p = p.copy(studentId = p.studentId.ifBlank { value })
                    label == "姓名" -> p = p.copy(name = p.name.ifBlank { value })
                    label == "性别" -> p = p.copy(gender = p.gender.ifBlank { value })
                    label == "身份证号" -> p = p.copy(idCard = p.idCard.ifBlank { value })
                    label == "考生号" -> p = p.copy(examNo = p.examNo.ifBlank { value })
                    label.contains("入学时间") || label.contains("入学日期") ||
                        label.contains("报到日期") -> p = p.copy(enrollDate = p.enrollDate.ifBlank { value })
                    label == "录取专业" || label == "专业" -> p = p.copy(major = p.major.ifBlank { value })
                    label == "学制" -> p = p.copy(duration = p.duration.ifBlank { value })
                    label.contains("培养层次") || label.contains("学历层次") || label == "层次" ->
                        p = p.copy(level = p.level.ifBlank { value })
                    label.contains("院(系)") || label.contains("院系") || label == "系部" || label == "学院" ->
                        p = p.copy(department = p.department.ifBlank { value })
                    label.contains("行政班级") || label == "班级" -> p = p.copy(className = p.className.ifBlank { value })
                    label.contains("生源单位") || label.contains("生源学校") ->
                        p = p.copy(sourceSchool = p.sourceSchool.ifBlank { value })
                }
            }
            i++
        }
        return p
    }

    private val SCRIPT = """
        var tries = 0;
        var timer = setInterval(function () {
          try {
            var tb = document.getElementById('tablesid');
            if (tb && tb.rows && tb.rows.length > 5) {
              var cells = [];
              document.querySelectorAll('td,th').forEach(function (c) {
                cells.push((c.innerText || '').trim());
              });
              clearInterval(timer);
              DSHBridge.post('__ID__', JSON.stringify(cells));
              return;
            }
          } catch (e) {}
          if (++tries > 60) { clearInterval(timer); DSHBridge.post('__ID__', '[]'); }
        }, 500);
    """.trimIndent()
}
