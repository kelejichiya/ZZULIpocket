package com.zzupocket.app.data.qingguo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/**
 * 向量由 Python 参考实现（XiQueEr2Ics 的 `jkingo_des.py`，逐行移植自 `jkingo.des.js`）生成。
 * 只要这里全绿，Kotlin 侧算出的 `params` 就与浏览器端一致。
 */
class KingoDesTest {

    @Test
    fun `matches reference vectors`() {
        val cases = listOf(
            Triple(
                "X3U9WVdSdGFXNDdPekV5TXpRMU5nPT0mX3A9YWJjJnJhbmRudW1iZXI9JmlzUGFzc3dvcmRQb2xpY3k9MQ==",
                "32834179289627581598814",
                "QzhCMDUwQkQyOEQ3RDBBNTcyQURCNDJEMzYxNURDQTE4MzFBMDZFRDAyQTFEOThFQzY0Qjg3RENDMzlCM0QxOEUwQzY4NDk1N0JDQzVDRTQyRTA1MTQxRTRCQjJBMDBENDNGRkVEMUQ2RjMxQjE3ODA0RTg5NzAxMDMzODk4MTlDRDIzRDU3REEzNDY3OEM3NEFDNzlBNDVCNzZBOTMwRDA2QTE5QjY0OEY0MjM2QkMyNTQ0MTFDRTlFMEQxQjI0NjA2NDE0ODY1OThBNDQwN0NGRjI1NTM0ODU4Q0RCMEM2NDk4QkZBOTk2ODg2N0EyNzMxNTg5NjBGQUU4NDI5RA==",
            ),
            Triple("aGVsbG8gd29ybGQ=", "op5nb9", "OEE5MjZCQjZCMDdCNjQ2QzkyQUFGRjZGNTUwQzc3MDk5Q0MwQzA2QkY0MkZGMDQw"),
            Triple("YQ==", "key", "MTZENzg3MEM5NjQ3NzYyQw=="),
            Triple("5Lit5paH5rWL6K+V", "12345678901234567890", "N0E3MjQ1N0IxMTkxQjY3Qw=="),
            Triple(
                "VGhlIHF1aWNrIGJyb3duIGZveCBqdW1wcyBvdmVyIHRoZSBsYXp5IGRvZw==",
                "ZZULI-2026",
                "NjE4RENCRUMwNzVFOTlENjI1Q0E3MTc0NzlBRDdDODZEMzIyODM1NkU5MEZCRTdBOTFBRkJFQjUzQkU5MTQzMkEyMDRGMkZDQjQ4ODg3QjBDODkyQ0Y4OTg0MzI1MkRGOTAwRDMzQkNCQjIyQzM0NTM0NkNFQzAyREQ2ODMxREQyMENFQTg2RTYwMDZBODg3NUE2MUYwRUNGMTYxQTIyRUIxNjAzNDRGNTNBNkY3RkM=",
            ),
        )
        for ((dataB64, key, expected) in cases) {
            val data = String(Base64.getDecoder().decode(dataB64), Charsets.UTF_8)
            assertEquals("case=${data.take(20)}", expected, KingoDes.encrypt(data, key))
        }
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals("", KingoDes.encrypt("", "key"))
        assertEquals("", KingoDes.encrypt("data", ""))
    }
}
