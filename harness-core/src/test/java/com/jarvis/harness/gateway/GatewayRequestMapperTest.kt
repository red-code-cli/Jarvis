package com.jarvis.harness.gateway

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRequestMapperTest {

    private val openAiTarget = GatewayTarget("custom", "Custom API", "gpt-x", "k", "Key", "https://api.example/v1", GatewayWire.OPENAI_CHAT)
    private val anthropicTarget = GatewayTarget("anthropic", "Anthropic", "claude-y", "k", "Key", "https://api.anthropic.com", GatewayWire.ANTHROPIC)

    @Test
    fun anthropicRequestIsMappedToOpenAiBody() {
        val anthropic = JSONObject()
            .put("model", "ignored-client-model")
            .put("stream", true)
            .put("max_tokens", 128)
            .put("system", "be terse")
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "user").put("content", "hello"))
                    .put(
                        JSONObject().put("role", "assistant").put(
                            "content",
                            JSONArray().put(
                                JSONObject().put("type", "tool_use").put("id", "toolu_1").put("name", "bash")
                                    .put("input", JSONObject().put("cmd", "ls")),
                            ),
                        ),
                    )
                    .put(
                        JSONObject().put("role", "user").put(
                            "content",
                            JSONArray().put(
                                JSONObject().put("type", "tool_result").put("tool_use_id", "toolu_1").put("content", "ok"),
                            ),
                        ),
                    ),
            )
            .put("tools", JSONArray().put(JSONObject().put("name", "bash").put("description", "run").put("input_schema", JSONObject().put("type", "object"))))

        val mapped = GatewayRequestMapper.buildUpstreamBody(anthropic, GatewayWire.ANTHROPIC, openAiTarget)

        assertEquals("gpt-x", mapped.getString("model"))
        assertFalse(mapped.getBoolean("stream"))
        val messages = mapped.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("be terse", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        val assistant = messages.getJSONObject(2)
        assertEquals("assistant", assistant.getString("role"))
        assertEquals("toolu_1", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("bash", assistant.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("tool", messages.getJSONObject(3).getString("role"))
        assertEquals("toolu_1", messages.getJSONObject(3).getString("tool_call_id"))
        assertEquals("bash", mapped.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
    }

    @Test
    fun openAiResponseBecomesAnthropicMessageWithToolUse() {
        val openAi = JSONObject()
            .put("id", "chatcmpl_1")
            .put("model", "gpt-x")
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("content", "done").put(
                            "tool_calls",
                            JSONArray().put(
                                JSONObject().put("id", "call_1").put(
                                    "function",
                                    JSONObject().put("name", "edit").put("arguments", """{"path":"a.txt"}"""),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            .put("usage", JSONObject().put("prompt_tokens", 3).put("completion_tokens", 5))

        val message = GatewayRequestMapper.openAiToAnthropicMessage(openAi, "claude-y")

        assertEquals("claude-y", message.getString("model"))
        assertEquals("tool_use", message.getString("stop_reason"))
        val content = message.getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("tool_use", content.getJSONObject(1).getString("type"))
        assertEquals("edit", content.getJSONObject(1).getString("name"))
        assertEquals("a.txt", content.getJSONObject(1).getJSONObject("input").getString("path"))
    }

    @Test
    fun duplicateToolCallsAreDroppedKeepingFirstSeenOrder() {
        val calls = JSONArray()
            .put(JSONObject().put("id", "a").put("function", JSONObject().put("name", "x")))
            .put(JSONObject().put("id", "a").put("function", JSONObject().put("name", "x")))
            .put(JSONObject().put("id", "b").put("function", JSONObject().put("name", "y")))

        val deduped = GatewayRequestMapper.dedupeToolCalls(calls)

        assertEquals(2, deduped.length())
        assertEquals("a", deduped.getJSONObject(0).getString("id"))
        assertEquals("b", deduped.getJSONObject(1).getString("id"))
    }

    @Test
    fun sameWireRequestOnlyOverridesModelAndDisablesUpstreamStream() {
        val openAi = JSONObject().put("model", "client").put("stream", true).put("messages", JSONArray())
        val mapped = GatewayRequestMapper.buildUpstreamBody(openAi, GatewayWire.OPENAI_CHAT, anthropicTarget)
        // OpenAI client against an Anthropic target is translated, not passed through.
        assertTrue(mapped.has("system") || mapped.has("messages"))
        assertFalse(mapped.getBoolean("stream"))
        assertEquals("claude-y", mapped.getString("model"))
    }

    @Test
    fun anthropicSseContainsMessageStartAndStop() {
        val message = JSONObject()
            .put("id", "msg_1").put("model", "claude-y").put("stop_reason", "end_turn")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "hi")))
            .put("usage", JSONObject().put("input_tokens", 1).put("output_tokens", 1))
        val frames = GatewayRequestMapper.anthropicSse(message).joinToString("")
        assertTrue(frames.contains("event: message_start"))
        assertTrue(frames.contains("event: message_stop"))
        assertTrue(frames.contains("text_delta"))
    }

    @Test
    fun upstreamPathMatchesWire() {
        assertEquals("/chat/completions", GatewayRequestMapper.upstreamPath(GatewayWire.OPENAI_CHAT))
        assertEquals("/messages", GatewayRequestMapper.upstreamPath(GatewayWire.ANTHROPIC))
    }
}
