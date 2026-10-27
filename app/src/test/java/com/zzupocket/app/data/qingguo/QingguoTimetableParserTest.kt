package com.zzupocket.app.data.qingguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QingguoTimetableParserTest {

    private val html = """
        <html><body>
        <table id="mytable">
          <tr><td>节次</td><td>星期一</td><td>星期二</td><td>星期三</td><td>星期四</td><td>星期五</td><td>星期六</td><td>星期日</td></tr>
          <tr>
            <td class="td1">1</td>
            <td class="td"><div style="padding-bottom:5px;clear:both;">
              <font style="font-weight: bolder">高等数学</font><br>张三<br>1-16周[1-2节]<br>科学校区 教三楼 201
            </div></td>
            <td class="td"></td><td class="td"></td><td class="td"></td><td class="td"></td><td class="td"></td><td class="td"></td>
          </tr>
          <tr>
            <td class="td1">3</td>
            <td class="td"></td>
            <td class="td"><div style="padding-bottom:5px;clear:both;">
              <font style="font-weight: bolder">大学物理</font><br>李四<br>1-8,10-16周[3-4节]<br>东校区 2 号楼 305
            </div></td>
            <td class="td"></td><td class="td"></td><td class="td"></td><td class="td"></td><td class="td"></td>
          </tr>
        </table>
        </body></html>
    """.trimIndent()

    @Test
    fun `parses courses with weeks teacher and room`() {
        val courses = QingguoTimetableParser.parse(html)
        assertEquals(2, courses.size)

        val math = courses.first { it.name == "高等数学" }
        assertEquals(1, math.weekday)
        assertEquals(1, math.startUnit)
        assertEquals(2, math.endUnit)
        assertEquals("张三", math.teacher)
        assertTrue(math.room!!.contains("教三楼"))
        assertEquals(16, math.weeks.size)
        assertEquals(1, math.weeks.first())
        assertEquals(16, math.weeks.last())

        val physics = courses.first { it.name == "大学物理" }
        assertEquals(2, physics.weekday)
        assertEquals(3, physics.startUnit)
        assertEquals(4, physics.endUnit)
        assertEquals(15, physics.weeks.size)
        assertTrue(9 !in physics.weeks)
    }

    @Test
    fun `missing weeks defaults to full term`() {
        val bare = """
            <table id="mytable"><tr><td class="td1">5</td>
            <td class="td"><div style="padding-bottom:5px;clear:both;"><font style="font-weight: bolder">大学英语</font></div></td>
            </tr></table>
        """.trimIndent()
        val one = QingguoTimetableParser.parse(bare).single()
        assertEquals("大学英语", one.name)
        assertEquals(1, one.weekday)
        // 表格一行 = 一个大节：第 5 大节对应第 9-10 小节
        assertEquals(9, one.startUnit)
        assertEquals(10, one.endUnit)
        assertEquals((1..18).toList(), one.weeks)
    }

    @Test
    fun `parses week expressions`() {
        assertEquals(listOf(1, 2, 3), QingguoTimetableParser.parseWeeks("1-3"))
        assertEquals(listOf(1, 3, 5), QingguoTimetableParser.parseWeeks("1,3,5"))
        assertEquals((1..8).toList() + (10..16).toList(), QingguoTimetableParser.parseWeeks("1-8,10-16"))
        assertEquals(emptyList<Int>(), QingguoTimetableParser.parseWeeks(null))
    }
}
