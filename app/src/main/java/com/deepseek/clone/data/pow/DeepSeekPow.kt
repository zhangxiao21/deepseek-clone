package com.deepseek.clone.data.pow

import android.util.Base64
import org.json.JSONObject

/**
 * DeepSeekHashV1 —— 经过真实抓包样本闭环验证的纯 Kotlin 实现。
 *
 * 本质：SHA3-256 的 sponge 结构，但 Keccak-f[1600] 只跑 23 轮（RC[1..23]，跳过 RC[0]）。
 * 输入拼接：salt + "_" + expireAt + "_" + nonce(十进制字符串)
 * 求解：找 [0, difficulty) 内第一个使 hash == challenge(32字节) 的 nonce，全 32 字节逐字节相等。
 *
 * 验证样本（来自 native .so 逆向 + pvuno/deepseek4free 权威 JS 实现）：
 *   prefix = "4a5a3ebe482ca78553f1_1781258202446_"
 *   DeepSeekHashV1(prefix + "102773") == 8868f19fb77e23cf1fc41d1af76a552bce58a24517d7e0920e9848c2013eba7c
 *   solvePow(challenge=上式, difficulty=144000) == 102773
 */
object DeepSeekPow {

    private const val RATE = 136
    private const val ROUNDS = 24

    // 标准 Keccak 轮常量，拆成 [lo, hi] 两个 32 位
    private val RC: Array<IntArray> = arrayOf(
        intArrayOf(0x00000001, 0x00000000), intArrayOf(0x00008082, 0x00000000),
        intArrayOf(0x0000808A, 0x80000000), intArrayOf(0x80008000, 0x80000000),
        intArrayOf(0x0000808B, 0x00000000), intArrayOf(0x80000001, 0x00000000),
        intArrayOf(0x80008081, 0x80000000), intArrayOf(0x00008009, 0x80000000),
        intArrayOf(0x0000008A, 0x00000000), intArrayOf(0x00000088, 0x00000000),
        intArrayOf(0x80008009, 0x00000000), intArrayOf(0x8000000A, 0x00000000),
        intArrayOf(0x8000808B, 0x00000000), intArrayOf(0x0000008B, 0x80000000),
        intArrayOf(0x00008089, 0x80000000), intArrayOf(0x00008003, 0x80000000),
        intArrayOf(0x00008002, 0x80000000), intArrayOf(0x00000080, 0x80000000),
        intArrayOf(0x0000800A, 0x00000000), intArrayOf(0x8000000A, 0x80000000),
        intArrayOf(0x80008081, 0x80000000), intArrayOf(0x00008080, 0x80000000),
        intArrayOf(0x80000001, 0x00000000), intArrayOf(0x80008008, 0x80000000)
    )

    // rho 旋转偏移（按 lane index 0..24，x + 5y 布局）
    private val RHO = intArrayOf(
        0, 1, 62, 28, 27, 36, 44, 6, 55, 20,
        3, 10, 43, 25, 39, 41, 45, 15, 21, 8,
        18, 2, 61, 56, 14
    )

    // PI 置换表
    private val PI = intArrayOf(
        0, 10, 20, 5, 15, 16, 1, 11, 21, 6,
        7, 17, 2, 12, 22, 23, 8, 18, 3, 13,
        14, 24, 9, 19, 4
    )

    /** Keccak-f[1600] 跑 23 轮（r=1..23），state 是 25 个 lane，每 lane 拆 lo/hi 存于 Uint32Array 语义 */
    private fun keccakF23(s: IntArray) {
        val b = IntArray(50)
        val c = IntArray(10)
        val d = IntArray(10)
        for (r in 1 until ROUNDS) {
            // θ
            for (x in 0 until 5) {
                val i = 2 * x
                c[i] = s[i] ^ s[i + 10] ^ s[i + 20] ^ s[i + 30] ^ s[i + 40]
                c[i + 1] = s[i + 1] ^ s[i + 11] ^ s[i + 21] ^ s[i + 31] ^ s[i + 41]
            }
            for (x in 0 until 5) {
                val p = 2 * ((x + 4) % 5)
                val q = 2 * ((x + 1) % 5)
                val i = 2 * x
                d[i] = c[p] xor (rotl32(c[q], c[q + 1], 1).first)
                d[i + 1] = c[p + 1] xor (rotl32(c[q], c[q + 1], 1).second)
            }
            for (i in 0 until 25) {
                val idx = 2 * (i % 5)
                s[2 * i] = s[2 * i] xor d[idx]
                s[2 * i + 1] = s[2 * i + 1] xor d[idx + 1]
            }
            // ρ + π
            for (i in 0 until 25) {
                val lo = s[2 * i]
                val hi = s[2 * i + 1]
                val rot = RHO[i]
                val (nlo, nhi) = rotl32(lo, hi, rot)
                val j = 2 * PI[i]
                b[j] = nlo
                b[j + 1] = nhi
            }
            // χ
            for (y in 0 until 25 step 5) {
                for (x in 0 until 5) {
                    val i = 2 * (y + x)
                    val i1 = 2 * (y + (x + 1) % 5)
                    val i2 = 2 * (y + (x + 2) % 5)
                    s[i] = b[i] xor (b[i1].inv() and b[i2])
                    s[i + 1] = b[i + 1] xor (b[i1 + 1].inv() and b[i2 + 1])
                }
            }
            // ι
            s[0] = s[0] xor RC[r][0]
            s[1] = s[1] xor RC[r][1]
        }
    }

    /** 64 位 lane 拆成 (lo,hi) 两个 32 位做循环左移，返回 (newLo, newHi) */
    private fun rotl32(lo: Int, hi: Int, rot: Int): Pair<Int, Int> {
        return when {
            rot == 0 -> lo to hi
            rot < 32 -> {
                val nlo = (lo shl rot) or (hi ushr (32 - rot))
                val nhi = (hi shl rot) or (lo ushr (32 - rot))
                nlo to nhi
            }
            rot == 32 -> hi to lo
            else -> {
                val r2 = rot - 32
                val nlo = (hi shl r2) or (lo ushr (32 - r2))
                val nhi = (lo shl r2) or (hi ushr (32 - r2))
                nlo to nhi
            }
        }
    }

    private fun absorbBlock(state: IntArray, buf: ByteArray, off: Int) {
        for (i in 0 until 17) {
            val p = off + i * 8
            val lo = (buf[p].toInt() and 0xff) or
                    ((buf[p + 1].toInt() and 0xff) shl 8) or
                    ((buf[p + 2].toInt() and 0xff) shl 16) or
                    ((buf[p + 3].toInt() and 0xff) shl 24)
            val hi = (buf[p + 4].toInt() and 0xff) or
                    ((buf[p + 5].toInt() and 0xff) shl 8) or
                    ((buf[p + 6].toInt() and 0xff) shl 16) or
                    ((buf[p + 7].toInt() and 0xff) shl 24)
            state[2 * i] = state[2 * i] xor lo
            state[2 * i + 1] = state[2 * i + 1] xor hi
        }
    }

    /** 计算 DeepSeekHashV1(data)，返回 32 字节 */
    fun hash(data: ByteArray): ByteArray {
        val state = IntArray(50)
        var off = 0
        while (off + RATE <= data.size) {
            absorbBlock(state, data, off)
            keccakF23(state)
            off += RATE
        }
        val tail = ByteArray(RATE)
        val rem = data.size - off
        if (rem > 0) System.arraycopy(data, off, tail, 0, rem)
        tail[rem] = 0x06.toByte()
        tail[RATE - 1] = (tail[RATE - 1].toInt() or 0x80).toByte()
        absorbBlock(state, tail, 0)
        keccakF23(state)
        return lanesToBytes(state)
    }

    private fun lanesToBytes(state: IntArray): ByteArray {
        val out = ByteArray(32)
        for (i in 0 until 4) {
            val lo = state[2 * i]
            val hi = state[2 * i + 1]
            out[i * 8] = (lo and 0xff).toByte()
            out[i * 8 + 1] = ((lo ushr 8) and 0xff).toByte()
            out[i * 8 + 2] = ((lo ushr 16) and 0xff).toByte()
            out[i * 8 + 3] = ((lo ushr 24) and 0xff).toByte()
            out[i * 8 + 4] = (hi and 0xff).toByte()
            out[i * 8 + 5] = ((hi ushr 8) and 0xff).toByte()
            out[i * 8 + 6] = ((hi ushr 16) and 0xff).toByte()
            out[i * 8 + 7] = ((hi ushr 24) and 0xff).toByte()
        }
        return out
    }

    fun buildPrefix(salt: String, expireAt: Long): String = "${salt}_${expireAt}_"

    /** 求解 nonce；找不到抛异常。 */
    fun solvePow(challenge: String, salt: String, expireAt: Long, difficulty: Long): Long {
        require(challenge.length == 64) { "pow: challenge 必须为 64 位 hex，实际 ${challenge.length} 位" }
        require(difficulty > 0) { "pow: difficulty 必须 > 0" }
        val target = hexToBytes(challenge)
        // 目标按小端拆成 8 个 32 位字
        val t = IntArray(8)
        for (i in 0 until 8) {
            t[i] = (target[i * 4].toInt() and 0xff) or
                    ((target[i * 4 + 1].toInt() and 0xff) shl 8) or
                    ((target[i * 4 + 2].toInt() and 0xff) shl 16) or
                    ((target[i * 4 + 3].toInt() and 0xff) shl 24)
        }

        val prefix = buildPrefix(salt, expireAt).toByteArray(Charsets.UTF_8)
        // 预吸收 prefix，得到 base state（prefix 通常 < 136，走单块尾部）
        val base = IntArray(50)
        var off = 0
        while (off + RATE <= prefix.size) {
            absorbBlock(base, prefix, off)
            keccakF23(base)
            off += RATE
        }
        val tailLen = prefix.size - off
        val tail = ByteArray(tailLen)
        if (tailLen > 0) System.arraycopy(prefix, off, tail, 0, tailLen)

        val scratch = ByteArray(RATE)
        var n = 0L
        while (n < difficulty) {
            val num = n.toString()
            val numLen = num.length
            val totalTail = tailLen + numLen
            val state = base.copyOf()
            if (totalTail < RATE) {
                java.util.Arrays.fill(scratch, 0.toByte())
                if (tailLen > 0) System.arraycopy(tail, 0, scratch, 0, tailLen)
                for (i in 0 until numLen) scratch[tailLen + i] = num[i].code.toByte()
                scratch[totalTail] = 0x06.toByte()
                scratch[RATE - 1] = (scratch[RATE - 1].toInt() or 0x80).toByte()
                absorbBlock(state, scratch, 0)
                keccakF23(state)
            } else {
                java.util.Arrays.fill(scratch, 0.toByte())
                if (tailLen > 0) System.arraycopy(tail, 0, scratch, 0, tailLen)
                val first = RATE - tailLen
                for (i in 0 until first) scratch[tailLen + i] = num[i].code.toByte()
                absorbBlock(state, scratch, 0)
                keccakF23(state)
                val rest = totalTail - RATE
                java.util.Arrays.fill(scratch, 0.toByte())
                var pos = first
                for (i in 0 until rest) scratch[i] = num[pos++].code.toByte()
                scratch[rest] = 0x06.toByte()
                scratch[RATE - 1] = (scratch[RATE - 1].toInt() or 0x80).toByte()
                absorbBlock(state, scratch, 0)
                keccakF23(state)
            }
            var match = true
            for (i in 0 until 8) {
                if (state[i] != t[i]) { match = false; break }
            }
            if (match) return n
            n++
        }
        throw IllegalStateException("pow: 在 difficulty=$difficulty 内未找到解")
    }

    /** 构造 X-Ds-Pow-Response 头（标准 base64，带 padding） */
    fun buildHeader(algorithm: String, challenge: String, salt: String,
                    signature: String, targetPath: String, answer: Long): String {
        val json = JSONObject().apply {
            put("algorithm", algorithm)
            put("challenge", challenge)
            put("salt", salt)
            put("answer", answer)
            put("signature", signature)
            put("target_path", targetPath)
        }.toString()
        return Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) +
                    Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xff))
        return sb.toString()
    }
}
