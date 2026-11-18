package com.deepseek.clone.pow

import com.deepseek.clone.data.pow.DeepSeekPow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PoW 单元测试：使用从 native .so 逆向 + pvuno/deepseek4free 权威实现交叉验证的真实样本。
 * 这些用例确保 Keccak-f23 魔改算法、nonce 求解、header 构造不被后续改动破坏。
 */
class DeepSeekPowTest {

    private val salt = "4a5a3ebe482ca78553f1"
    private val expireAt = 1781258202446L
    private val challenge = "8868f19fb77e23cf1fc41d1af76a552bce58a24517d7e0920e9848c2013eba7c"

    @Test
    fun hash_matchesRealSample() {
        val input = (DeepSeekPow.buildPrefix(salt, expireAt) + "102773").toByteArray(Charsets.UTF_8)
        val got = DeepSeekPow.bytesToHex(DeepSeekPow.hash(input))
        assertEquals(challenge, got)
    }

    @Test
    fun solve_findsExpectedNonce() {
        val answer = DeepSeekPow.solvePow(challenge, salt, expireAt, 144000L)
        assertEquals(102773L, answer)
    }

    @Test
    fun buildHeader_isBase64JsonWithAnswer() {
        val header = DeepSeekPow.buildHeader(
            algorithm = "DeepSeekHashV1",
            challenge = challenge,
            salt = salt,
            signature = "sig123",
            targetPath = "/api/v0/chat/completion",
            answer = 102773L
        )
        val decoded = String(java.util.Base64.getDecoder().decode(header), Charsets.UTF_8)
        assert(decoded.contains("\"answer\":102773")) { "header 缺少 answer: $decoded" }
        assert(decoded.contains("DeepSeekHashV1")) { "header 缺少 algorithm: $decoded" }
        assert(decoded.contains("/api/v0/chat/completion")) { "header 缺少 target_path: $decoded" }
    }
}
