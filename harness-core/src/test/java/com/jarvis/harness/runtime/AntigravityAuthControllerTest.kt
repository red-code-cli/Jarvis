package com.jarvis.harness.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Antigravity sign-in only ever drives agy's own official OAuth flow, so the
 * app parses the browser URL and the "am I signed in?" answer out of agy's
 * terminal output. These are the parsing rules that make that safe.
 */
class AntigravityAuthControllerTest {

    @Test
    fun `extracts google authorization url with pkce`() {
        val url = "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=123.apps.googleusercontent.com&response_type=code" +
            "&scope=openid&code_challenge=abcDEF123&state=stateValue-1"
        val extracted = extractGoogleOAuthUrl("Please visit: $url\n")
        assertEquals(url, extracted)
    }

    @Test
    fun `extracts url that the renderer wrapped across terminal rows`() {
        val wrapped = "Open this URL:\n" +
            "https://accounts.google.com/o/oauth2/v2/auth?client_id=1.apps.googleusercontent.com\n" +
            "&code_challenge=xyz\n" +
            "&state=st\n"
        val extracted = extractGoogleOAuthUrl(wrapped) ?: error("expected an authorization URL")
        assertTrue(extracted.startsWith("https://accounts.google.com/"))
        assertTrue(extracted.contains("client_id="))
        assertTrue(extracted.contains("code_challenge="))
    }
    @Test
    fun `does not treat the login menu help text as an authorization url`() {
        val output = "Select login method\n> 1. Google OAuth\n  2. API key\n"
        assertNull(extractGoogleOAuthUrl(output))
    }

    @Test
    fun `sign in check treats missing login as signed out`() {
        assertEquals(
            AntigravitySignInCheck.SIGNED_OUT,
            antigravitySignInCheck("Please sign in to use Antigravity CLI.", 1),
        )
        assertEquals(
            AntigravitySignInCheck.SIGNED_OUT,
            antigravitySignInCheck("Authentication required", 1),
        )
    }

    @Test
    fun `sign in check treats a clean model list as signed in`() {
        assertEquals(
            AntigravitySignInCheck.SIGNED_IN,
            antigravitySignInCheck("gemini-2.5-pro\nclaude-sonnet", 0),
        )
    }

    @Test
    fun `sign in check never signs the user out on network trouble`() {
        assertEquals(
            AntigravitySignInCheck.UNKNOWN,
            antigravitySignInCheck("getaddrinfo ENOTFOUND accounts.google.com", 1),
        )
    }
}
