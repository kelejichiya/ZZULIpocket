package com.zzupocket.app.data.qingguo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 登录用的 MD5 必须与浏览器端 `hex_md5` 一致。
 *
 * 这里单独测一遍是因为 Kotlin 的 `"%02x".format(byte)` 对负数字节的处理很容易写错，
 * 一旦变成 8 位十六进制，整个 token/密码哈希都会悄悄失效（服务端只会回一句
 * "经非法处理的登录信息"，很难定位）。
 */
class QingguoHashTest {

    @Test
    fun `md5 matches standard vectors`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", QingguoClient.md5(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", QingguoClient.md5("abc"))
        assertEquals("e10adc3949ba59abbe56e057f20f883e", QingguoClient.md5("123456"))
    }

    @Test
    fun `md5 handles utf8 text`() {
        // echo -n "中文" | md5sum
        assertEquals("a7bac2239fcdcb3a067903d8077c4a07", QingguoClient.md5("中文"))
    }
}
