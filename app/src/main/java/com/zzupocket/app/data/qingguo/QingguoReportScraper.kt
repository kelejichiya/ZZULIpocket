package com.zzupocket.app.data.qingguo

import com.zzupocket.app.core.school.SchoolProfile
import kotlinx.serialization.json.Json

/**
 * 成绩 / 考试的原生数据源：在已登录的 WebView 里跑学校页面的 JS（选条件、点检索），
 * 再把报表 iframe 里的表格抓成二维字符串数组。
 *
 * 这样既不用密码、也不用猜接口参数，拿到的就是学校页面上真实显示的数据。
 */
object QingguoReportScraper {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 一个学期的成绩；term 是报表里「学年学期：…」的原文 */
    data class GradeSection(val term: String, val rows: List<List<String>>)

    /**
     * 登录后从学籍页 DOM 里直接抓学号（比在 HTML 源码里正则稳，页面渲染后的文本一定有）。
     * 抓不到返回 null，调用方再走"补填学号"兜底。
     */
    suspend fun discoverAccount(): String? = discoverAccountDetailed().first

    /** 返回（学号，页面文本长度，最终地址），用于失败时排查 */
    suspend fun discoverAccountDetailed(): Triple<String?, Int, String> {
        val script = """
            function collectText() {
              var parts = [];
              try { if (document.body) { parts.push(document.body.innerText || ''); } } catch (e) {}
              try {
                var inputs = document.querySelectorAll('input');
                for (var i = 0; i < inputs.length; i++) {
                  var n = (inputs[i].name || '') + (inputs[i].id || '');
                  if (n.toLowerCase().indexOf('xh') >= 0 || n.indexOf('学号') >= 0) {
                    parts.push('学号 ' + (inputs[i].value || ''));
                  }
                }
              } catch (e) {}
              try {
                var fs = document.querySelectorAll('iframe,frame');
                for (var j = 0; j < fs.length; j++) {
                  try {
                    var d = fs[j].contentDocument;
                    if (d && d.body) { parts.push(d.body.innerText || ''); }
                  } catch (e) {}
                }
              } catch (e) {}
              return parts.join('\n');
            }
            var tries = 0;
            var timer = setInterval(function () {
              try {
                var text = collectText();
                var compact = text.replace(/[\s\u00a0]+/g, '');
                var m = compact.match(/学号[^0-9]{0,40}(\d{10,14})/);
                if (!m) { m = text.match(/学\s*号[^0-9]{0,40}(\d{10,14})/); }
                if (m) {
                  clearInterval(timer);
                  DSHBridge.post('__ID__', JSON.stringify({id: m[1], len: text.length, url: location.href}));
                  return;
                }
              } catch (e) {}
              if (++tries > 40) {
                clearInterval(timer);
                DSHBridge.post('__ID__', JSON.stringify({id: '', len: collectText().length, url: location.href}));
              }
            }, 300);
        """.trimIndent()
        val raw = QingguoWebSession.runScript(SchoolProfile.profilePath, script, timeoutMs = 20_000)
            ?: return Triple(null, 0, "WebView 超时")
        android.util.Log.i("QingguoAccount", "dom=$raw")
        return runCatching {
            val o = org.json.JSONObject(raw)
            val id = o.optString("id").takeIf { Regex("""\d{10,14}""").matches(it) }
            Triple(id, o.optInt("len"), o.optString("url"))
        }.getOrElse { Triple(null, 0, "解析失败：$raw") }
    }

    suspend fun grades(): List<GradeSection> {
        val raw = QingguoWebSession.runScript(SchoolProfile.gradePath, GRADE_SCRIPT, timeoutMs = 45_000)
        android.util.Log.i("QingguoGrade", "rawLen=${raw?.length} head=${raw?.take(120)}")
        val sections = parseSections(raw)
        if (sections.isNotEmpty()) return sections

        // 兜底：分组脚本没拿到（报表结构/时序问题）时，退回整表抓取，至少别显示"没有成绩"
        val flat = QingguoWebSession.runScript(SchoolProfile.gradePath, FLAT_SCRIPT, timeoutMs = 30_000)
            ?: return emptyList()
        val rows = runCatching { json.decodeFromString<List<List<String>>>(flat) }.getOrDefault(emptyList())
        android.util.Log.i("QingguoGrade", "fallback rows=${rows.size}")
        return if (rows.isEmpty()) emptyList() else listOf(GradeSection("", rows))
    }

    private fun parseSections(raw: String?): List<GradeSection> {
        if (raw == null) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(raw)
            (root as? kotlinx.serialization.json.JsonArray)?.mapNotNull { element ->
                val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val term = (obj["term"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val rowsArr = obj["rows"] as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
                val rows = rowsArr.map { r ->
                    (r as? kotlinx.serialization.json.JsonArray)?.map { c ->
                        (c as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    }.orEmpty()
                }
                GradeSection(term, rows)
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    suspend fun exams(): List<List<String>> {
        val raw = QingguoWebSession.runScript(SchoolProfile.examPath, EXAM_SCRIPT, timeoutMs = 60_000)
            ?: return emptyList()
        val rows = runCatching { json.decodeFromString<List<List<String>>>(raw) }.getOrDefault(emptyList())
        android.util.Log.i("QingguoExam", "抓取到 ${rows.size} 行，前两行=${rows.take(2)}")
        return rows
    }

    private suspend fun run(script: String, path: String): List<List<String>> {
        val raw = QingguoWebSession.runScript(path, script) ?: return emptyList()
        return runCatching { json.decodeFromString<List<List<String>>>(raw) }.getOrDefault(emptyList())
    }

    /** 成绩：切到「入学以来」并检索；按报表里的「学年学期」标题与表格顺序一一对应分组 */
    private val GRADE_SCRIPT = """
        try {
          var r = document.getElementById('sjxz1');
          if (r) { r.checked = true; }
          var b = document.getElementById('btnQry');
          if (b) { b.click(); }
        } catch (e) {}
        var tries = 0;
        var timer = setInterval(function () {
          try {
            var f = document.getElementsByName('frmReport')[0];
            var d = f && f.contentDocument;
            if (d && d.querySelector('table')) {
              var tables = [].slice.call(d.querySelectorAll('table')).filter(function (tb) {
                var t = tb.innerText || '';
                return t.indexOf('课程') >= 0 || t.indexOf('学分') >= 0 || t.indexOf('成绩') >= 0;
              });
              if (tables.length === 0) {
                tables = [].slice.call(d.querySelectorAll('table')).filter(function (tb) {
                  return ((tb.innerText || '').split('\n').length) > 3;
                });
              }
              if (tables.length === 0) { return; }
              var allTables = [].slice.call(d.querySelectorAll('table'));
              var sections = tables.map(function (tb) {
                // 标题就在"这张成绩表之前最近的、包含 学年学期 的表格"里
                var term = '';
                var idx = allTables.indexOf(tb);
                for (var j = idx - 1; j >= 0; j--) {
                  var t = allTables[j].innerText || '';
                  var m = t.match(/学年学期[：:]?\s*([0-9]{4}-[0-9]{4}\s*学年\s*第?[一二三]学期)/);
                  if (!m) { m = t.match(/学年学期[：:]?\s*([^\n]{0,40})/); }
                  if (m) { term = m[1]; break; }
                }
                if (!term) {
                  var own = (tb.innerText || '').match(/学年学期[：:]?\s*([^\n]{0,40})/);
                  if (own) { term = own[1]; }
                }
                if (!term) {
                  var bodyText = d.body.innerText || '';
                  var bm = bodyText.match(/学年学期[：:]?\s*([0-9]{4}-[0-9]{4}\s*学年\s*第?[一二三]学期)/);
                  if (bm) { term = bm[1]; }
                }
                var rows = [];
                tb.querySelectorAll('tr').forEach(function (tr) {
                  var cells = [];
                  tr.querySelectorAll('td,th').forEach(function (c) {
                    cells.push((c.innerText || '').trim());
                  });
                  if (cells.length > 0) { rows.push(cells); }
                });
                return { term: term, rows: rows };
              });
              clearInterval(timer);
              DSHBridge.post('__ID__', JSON.stringify(sections));
              return;
            }
          } catch (e) {}
          if (++tries > 100) { clearInterval(timer); DSHBridge.post('__ID__', '[]'); }
        }, 300);
    """.trimIndent()

    /** 兜底：整表抓取（旧逻辑） */
    private val FLAT_SCRIPT = """
        try {
          var r = document.getElementById('sjxz1');
          if (r) { r.checked = true; }
          var b = document.getElementById('btnQry');
          if (b) { b.click(); }
        } catch (e) {}
        var tries = 0;
        var timer = setInterval(function () {
          try {
            var f = document.getElementsByName('frmReport')[0];
            var d = f && f.contentDocument;
            if (d && d.querySelector('table')) {
              var rows = [];
              d.querySelectorAll('table tr').forEach(function (tr) {
                var cells = [];
                tr.querySelectorAll('td,th').forEach(function (c) {
                  cells.push((c.innerText || '').trim());
                });
                if (cells.length > 0) { rows.push(cells); }
              });
              clearInterval(timer);
              DSHBridge.post('__ID__', JSON.stringify(rows));
              return;
            }
          } catch (e) {}
          if (++tries > 100) { clearInterval(timer); DSHBridge.post('__ID__', '[]'); }
        }, 300);
    """.trimIndent()

    /** 考试：等两个下拉框就绪 → 选第一个学年学期/轮次 → 检索 → 取表 */
    private val EXAM_SCRIPT = """
        function pick(sel) {
          for (var i = 0; i < sel.options.length; i++) {
            if (sel.options[i].value) { sel.selectedIndex = i; return true; }
          }
          return sel.options.length > 0;
        }
        function grab() {
          var tries = 0;
          var timer = setInterval(function () {
            try {
              var f = document.getElementsByName('frmReport')[0];
              var d = f && f.contentDocument;
              if (d && d.querySelector('table')) {
                var rows = [];
                d.querySelectorAll('table tr').forEach(function (tr) {
                  var cells = [];
                  tr.querySelectorAll('td,th').forEach(function (c) {
                    cells.push((c.innerText || '').trim());
                  });
                  if (cells.length > 0) { rows.push(cells); }
                });
                clearInterval(timer);
                DSHBridge.post('__ID__', JSON.stringify(rows));
              }
            } catch (e) {}
            if (++tries > 80) { clearInterval(timer); DSHBridge.post('__ID__', '[]'); }
          }, 400);
        }
        var boot = 0;
        var bootTimer = setInterval(function () {
          try {
            var x = document.getElementById('xnxq');
            var k = document.getElementById('kslc');
            if (x && k && x.options.length > 0) {
              clearInterval(bootTimer);
              pick(x);
              try { x.dispatchEvent(new Event('change')); } catch (e) {}
              var waitK = 0;
              var timerK = setInterval(function () {
                if (k.options.length > 0) {
                  clearInterval(timerK);
                  pick(k);
                  var b = document.getElementById('btnQry');
                  if (b) { b.click(); }
                  grab();
                }
                if (++waitK > 80) { clearInterval(timerK); DSHBridge.post('__ID__', '[]'); }
              }, 300);
            }
          } catch (e) {}
          if (++boot > 60) { clearInterval(bootTimer); DSHBridge.post('__ID__', '[]'); }
        }, 500);
    """.trimIndent()
}
