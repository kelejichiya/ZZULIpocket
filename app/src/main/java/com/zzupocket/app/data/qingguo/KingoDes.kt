package com.zzupocket.app.data.qingguo

import java.util.Base64

/**
 * 青果 KINGOSOFT 登录参数加密（`jkingo.des.js` 的 Kotlin 移植）。
 *
 * 这不是标准 DES：密钥按 4 字符切块、每块单独生成 16 组子密钥后**逐块串联加密**
 * （`strEnc(data, key)` 的行为），最后输出大写 hex 再 Base64。服务端按同一套实现解密，
 * 所以不能替换成 `javax.crypto` 的标准 DES。
 *
 * 纯函数、无 Android 依赖，单测见 `KingoDesTest`（向量由 Python 参考实现生成）。
 */
object KingoDes {

    private val SBOXES: Array<Array<IntArray>> = arrayOf(
        arrayOf(
            intArrayOf(14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7),
            intArrayOf(0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8),
            intArrayOf(4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0),
            intArrayOf(15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13),
        ),
        arrayOf(
            intArrayOf(15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10),
            intArrayOf(3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5),
            intArrayOf(0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15),
            intArrayOf(13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9),
        ),
        arrayOf(
            intArrayOf(10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8),
            intArrayOf(13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1),
            intArrayOf(13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7),
            intArrayOf(1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12),
        ),
        arrayOf(
            intArrayOf(7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15),
            intArrayOf(13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9),
            intArrayOf(10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4),
            intArrayOf(3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14),
        ),
        arrayOf(
            intArrayOf(2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9),
            intArrayOf(14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6),
            intArrayOf(4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14),
            intArrayOf(11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3),
        ),
        arrayOf(
            intArrayOf(12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11),
            intArrayOf(10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8),
            intArrayOf(9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6),
            intArrayOf(4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13),
        ),
        arrayOf(
            intArrayOf(4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1),
            intArrayOf(13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6),
            intArrayOf(1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2),
            intArrayOf(6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12),
        ),
        arrayOf(
            intArrayOf(13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7),
            intArrayOf(1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2),
            intArrayOf(7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8),
            intArrayOf(2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11),
        ),
    )

    private val P_SOURCE = intArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
        1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24,
    )

    private val FP_SOURCE = intArrayOf(
        39, 7, 47, 15, 55, 23, 63, 31,
        38, 6, 46, 14, 54, 22, 62, 30,
        37, 5, 45, 13, 53, 21, 61, 29,
        36, 4, 44, 12, 52, 20, 60, 28,
        35, 3, 43, 11, 51, 19, 59, 27,
        34, 2, 42, 10, 50, 18, 58, 26,
        33, 1, 41, 9, 49, 17, 57, 25,
        32, 0, 40, 8, 48, 16, 56, 24,
    )

    private val KEY_LOOP = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

    private val PC2 = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
        25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
        50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )

    private const val HEX = "0123456789ABCDEF"

    /** `jkingo.des.js` 的 `strEnc(data, key)`，返回大写 hex。 */
    fun strEnc(data: String, key: String): String {
        if (data.isEmpty() || key.isEmpty()) return ""
        val keyBytes = getKeyBytes(key)
        val out = StringBuilder()
        var offset = 0
        while (offset < data.length) {
            val end = minOf(offset + 4, data.length)
            var bits = strToBt(data.substring(offset, end))
            for (kb in keyBytes) bits = enc(bits, kb)
            out.append(bt64ToHex(bits))
            offset = end
        }
        return out.toString()
    }

    /** 登录参数最终形态：hex 串的 Base64（服务端先 Base64 解码，再按 hex 解 DES）。 */
    fun encrypt(data: String, key: String): String {
        if (data.isEmpty() || key.isEmpty()) return ""
        return Base64.getEncoder().encodeToString(strEnc(data, key).toByteArray(Charsets.US_ASCII))
    }

    private fun strToBt(s: String): IntArray {
        val bits = IntArray(64)
        val len = minOf(s.length, 4)
        for (i in 0 until len) {
            val code = s[i].code
            for (j in 0 until 16) {
                bits[16 * i + j] = (code shr (15 - j)) and 1
            }
        }
        return bits
    }

    private fun getKeyBytes(key: String): List<IntArray> {
        val list = ArrayList<IntArray>(key.length / 4 + 1)
        var i = 0
        while (i < key.length) {
            val end = minOf(i + 4, key.length)
            list.add(strToBt(key.substring(i, end)))
            i = end
        }
        return list
    }

    private fun initPermute(data: IntArray): IntArray {
        val out = IntArray(64)
        var m = 1
        var n = 0
        for (i in 0 until 4) {
            var k = 0
            for (j in 7 downTo 0) {
                out[i * 8 + k] = data[j * 8 + m]
                out[i * 8 + k + 32] = data[j * 8 + n]
                k++
            }
            m += 2
            n += 2
        }
        return out
    }

    private fun expandPermute(right: IntArray): IntArray {
        val out = IntArray(48)
        for (i in 0 until 8) {
            out[i * 6 + 0] = if (i == 0) right[31] else right[i * 4 - 1]
            out[i * 6 + 1] = right[i * 4 + 0]
            out[i * 6 + 2] = right[i * 4 + 1]
            out[i * 6 + 3] = right[i * 4 + 2]
            out[i * 6 + 4] = right[i * 4 + 3]
            out[i * 6 + 5] = if (i == 7) right[0] else right[i * 4 + 4]
        }
        return out
    }

    private fun sBoxPermute(expand: IntArray): IntArray {
        val out = IntArray(32)
        for (m in 0 until 8) {
            val row = expand[m * 6 + 0] * 2 + expand[m * 6 + 5]
            val col = expand[m * 6 + 1] * 8 + expand[m * 6 + 2] * 4 +
                expand[m * 6 + 3] * 2 + expand[m * 6 + 4]
            val `val` = SBOXES[m][row][col]
            out[m * 4 + 0] = (`val` shr 3) and 1
            out[m * 4 + 1] = (`val` shr 2) and 1
            out[m * 4 + 2] = (`val` shr 1) and 1
            out[m * 4 + 3] = `val` and 1
        }
        return out
    }

    private fun pPermute(sBox: IntArray): IntArray = IntArray(32) { sBox[P_SOURCE[it]] }

    private fun finallyPermute(end: IntArray): IntArray = IntArray(64) { end[FP_SOURCE[it]] }

    private fun generateKeys(keyByte: IntArray): Array<IntArray> {
        val key = IntArray(56)
        for (i in 0 until 7) {
            for (j in 0 until 8) {
                key[i * 8 + j] = keyByte[8 * (7 - j) + i]
            }
        }
        val keys = Array(16) { IntArray(48) }
        for (i in 0 until 16) {
            repeat(KEY_LOOP[i]) {
                val tempLeft = key[0]
                val tempRight = key[28]
                for (k in 0 until 27) {
                    key[k] = key[k + 1]
                    key[28 + k] = key[29 + k]
                }
                key[27] = tempLeft
                key[55] = tempRight
            }
            for (k in 0 until 48) keys[i][k] = key[PC2[k]]
        }
        return keys
    }

    private fun enc(data: IntArray, keyByte: IntArray): IntArray {
        val keys = generateKeys(keyByte)
        val ip = initPermute(data)
        var left = ip.copyOfRange(0, 32)
        var right = ip.copyOfRange(32, 64)
        for (i in 0 until 16) {
            val nextLeft = right
            val f = pPermute(sBoxPermute(xor(expandPermute(right), keys[i])))
            val nextRight = xor(f, left)
            left = nextLeft
            right = nextRight
        }
        return finallyPermute(right + left)
    }

    private fun xor(a: IntArray, b: IntArray): IntArray = IntArray(a.size) { a[it] xor b[it] }

    private fun bt64ToHex(bits: IntArray): String {
        val sb = StringBuilder(16)
        for (i in 0 until 16) {
            val idx = bits[i * 4] * 8 + bits[i * 4 + 1] * 4 + bits[i * 4 + 2] * 2 + bits[i * 4 + 3]
            sb.append(HEX[idx])
        }
        return sb.toString()
    }
}
