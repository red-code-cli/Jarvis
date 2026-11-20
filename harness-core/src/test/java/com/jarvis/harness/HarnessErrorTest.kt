package com.jarvis.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider error taxonomy. These cover the two reported field failures:
 *   (a) HTTP 429 rate limit  -> retryable, never a bare "Connection failed"
 *   (b) invalid/expired key  -> not retryable, actionable Settings hint
 */
class HarnessErrorTest {

    private fun classify(text: String, provider: String = "Anthropic API") =
        HarnessError.classify(text, provider)

    @Test
    fun `http 429 is a retryable rate limit with status and provider`() {
        val error = classify("""{"type":"error","error":{"type":"rate_limit_error","message":"Number of requests exceeded"}} HTTP 429""")
        assertEquals(HarnessErrorKind.RATE_LIMIT, error.kind)
        assertTrue(error.kind.retryable)
        assertEquals(429, error.httpStatus)
        assertEquals("Anthropic API", error.provider)
        assertTrue("headline must name provider + status", error.headline.contains("Anthropic API") && error.headline.contains("429"))
        assertTrue("spoken text must say what to do", error.spoken.contains("retry", ignoreCase = true))
    }

    @Test
    fun `401 and 403 are invalid key and not retryable`() {
        listOf("HTTP 401 invalid x-api-key", "status 403 forbidden", "invalid api key").forEach { raw ->
            val error = classify(raw)
            assertEquals("for: $raw", HarnessErrorKind.INVALID_KEY, error.kind)
            assertFalse(error.kind.retryable)
            assertTrue(error.hint.contains("Settings", ignoreCase = true))
        }
    }

    @Test
    fun `server and network failures are retryable`() {
        assertEquals(HarnessErrorKind.SERVER, classify("HTTP 503 service unavailable").kind)
        assertEquals(HarnessErrorKind.NETWORK, classify("Unable to resolve host api.anthropic.com").kind)
        assertEquals(HarnessErrorKind.TIMEOUT, classify("request timed out after 45s").kind)
        assertTrue(classify("HTTP 500").kind.retryable)
        assertTrue(classify("connection reset by peer").kind.retryable)
    }

    @Test
    fun `quota wording without a status is still a rate limit`() {
        assertEquals(HarnessErrorKind.RATE_LIMIT, classify("quota exceeded for this key").kind)
        assertEquals(HarnessErrorKind.RATE_LIMIT, classify("RESOURCE_EXHAUSTED: too many requests").kind)
    }

    @Test
    fun `secrets are scrubbed from stored and spoken text`() {
        val raw = "Authorization: Bearer sk-ant-api03-SECRETSECRET1234 failed, key sk-abcdefgh12345678"
        val error = classify(raw)
        assertFalse(error.raw.contains("SECRETSECRET1234"))
        assertFalse(error.spoken.contains("SECRETSECRET1234"))
        assertTrue(error.raw.contains("••••"))
    }

    @Test
    fun `unknown provider text still yields provider and action`() {
        val error = classify("something odd happened")
        assertEquals(HarnessErrorKind.UNKNOWN, error.kind)
        assertEquals("Anthropic API", error.provider)
        assertTrue(error.hint.isNotBlank())
    }
}
