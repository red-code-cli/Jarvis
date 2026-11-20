package com.jarvis.harness.gateway

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Pure translation between the two wire protocols the gateway exposes and the providers it
 * calls. Also owns the "safe resume" rules: upstream is always called non-streaming so a
 * response is only written to the client after it has fully succeeded, which means a broken
 * stream can never duplicate tool calls.
 *
 * No Android or network APIs live here, so every rule is unit-tested directly.
 */
object GatewayRequestMapper {

    /** Path appended to a target base URL for the request. */
    fun upstreamPath(wire: GatewayWire): String = when (wire) {
        GatewayWire.OPENAI_CHAT -> "/chat/completions"
        GatewayWire.ANTHROPIC -> "/messages"
    }

    /**
     * Build the upstream request body for [target] from the client's raw body.
     *
     * Upstream streaming is always disabled: [LocalProviderGateway] synthesizes the client's
     * stream after a success, so failover happens before any byte reaches the client.
     */
    fun buildUpstreamBody(clientBody: JSONObject, clientWire: GatewayWire, target: GatewayTarget): JSONObject {
        val mapped = when {
            clientWire == GatewayWire.ANTHROPIC && target.wire == GatewayWire.OPENAI_CHAT ->
                anthropicToOpenAi(clientBody, target.model)
            clientWire == GatewayWire.OPENAI_CHAT && target.wire == GatewayWire.ANTHROPIC ->
                openAiToAnthropic(clientBody, target.model)
            else -> JSONObject(clientBody.toString())
        }
        mapped.put("stream", false)
        if (target.model.isNotBlank()) mapped.put("model", target.model)
        return mapped
    }

    /** Convert a non-streaming upstream success into the client's expected response shape. */
    fun buildClientBody(
        upstreamBody: JSONObject,
        upstreamWire: GatewayWire,
        clientWire: GatewayWire,
        model: String,
    ): JSONObject = when {
        upstreamWire == clientWire -> JSONObject(upstreamBody.toString()).also { it.put("model", model) }
        upstreamWire == GatewayWire.OPENAI_CHAT && clientWire == GatewayWire.ANTHROPIC ->
            openAiToAnthropicMessage(upstreamBody, model)
        else -> upstreamBody
    }

    // ── Anthropic client → OpenAI upstream ───────────────────────────────────

    fun anthropicToOpenAi(source: JSONObject, model: String): JSONObject {
        val target = JSONObject()
            .put("model", normalizeModel(model))
            .put("stream", false)
            .put("max_tokens", source.optInt("max_tokens", 4096))
        if (source.has("temperature")) target.put("temperature", source.get("temperature"))
        val messages = JSONArray()
        source.opt("system")?.let { system ->
            val text = when (system) {
                is JSONArray -> contentText(system)
                else -> system.toString()
            }
            if (text.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", text))
        }
        val sourceMessages = source.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until sourceMessages.length()) {
            val message = sourceMessages.optJSONObject(index) ?: continue
            val role = message.optString("role")
            val content = message.opt("content")
            if (content !is JSONArray) {
                messages.put(JSONObject().put("role", role).put("content", content ?: ""))
                continue
            }
            val text = contentText(content)
            val toolCalls = JSONArray()
            val toolResults = mutableListOf<JSONObject>()
            for (partIndex in 0 until content.length()) {
                val part = content.optJSONObject(partIndex) ?: continue
                when (part.optString("type")) {
                    "tool_use" -> toolCalls.put(
                        JSONObject().put("id", part.optString("id"))
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", part.optString("name"))
                                    .put("arguments", part.optJSONObject("input")?.toString() ?: "{}"),
                            ),
                    )
                    "tool_result" -> toolResults += JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", part.optString("tool_use_id"))
                        .put("content", valueText(part.opt("content")))
                }
            }
            if (text.isNotBlank() || toolCalls.length() > 0) {
                val converted = JSONObject().put("role", role).put("content", text.ifBlank { JSONObject.NULL })
                if (toolCalls.length() > 0) converted.put("tool_calls", toolCalls)
                messages.put(converted)
            }
            toolResults.forEach(messages::put)
        }
        target.put("messages", messages)
        source.optJSONArray("tools")?.let { tools ->
            val converted = JSONArray()
            for (index in 0 until tools.length()) {
                val tool = tools.getJSONObject(index)
                converted.put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject()
                            .put("name", tool.optString("name"))
                            .put("description", tool.optString("description"))
                            .put("parameters", tool.optJSONObject("input_schema") ?: JSONObject().put("type", "object")),
                    ),
                )
            }
            target.put("tools", converted).put("tool_choice", "auto")
        }
        return target
    }

    /** OpenAI `chat/completions` response → Anthropic `messages` response. */
    fun openAiToAnthropicMessage(source: JSONObject, model: String): JSONObject {
        val message = source.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: JSONObject()
        val content = JSONArray()
        val text = message.optString("content")
        if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))
        val calls = dedupeToolCalls(message.optJSONArray("tool_calls"))
        for (index in 0 until calls.length()) {
            val call = calls.getJSONObject(index)
            val function = call.optJSONObject("function") ?: JSONObject()
            val arguments = runCatching { JSONObject(function.optString("arguments", "{}")) }.getOrDefault(JSONObject())
            content.put(
                JSONObject().put("type", "tool_use")
                    .put("id", call.optString("id").ifBlank { "tool_${UUID.randomUUID()}" })
                    .put("name", function.optString("name"))
                    .put("input", arguments),
            )
        }
        val usage = source.optJSONObject("usage") ?: JSONObject()
        return JSONObject().put("id", source.optString("id").ifBlank { "msg_${UUID.randomUUID()}" })
            .put("type", "message").put("role", "assistant").put("model", model)
            .put("content", content)
            .put("stop_reason", if (calls.length() > 0) "tool_use" else "end_turn")
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject().put("input_tokens", usage.optInt("prompt_tokens")).put("output_tokens", usage.optInt("completion_tokens")))
    }

    // ── OpenAI client → Anthropic upstream ───────────────────────────────────

    fun openAiToAnthropic(source: JSONObject, model: String): JSONObject {
        val target = JSONObject()
            .put("model", normalizeModel(model))
            .put("stream", false)
            .put("max_tokens", source.optInt("max_tokens", 4096))
        if (source.has("temperature")) target.put("temperature", source.get("temperature"))
        val messages = JSONArray()
        val sourceMessages = source.optJSONArray("messages") ?: JSONArray()
        val systemText = StringBuilder()
        for (index in 0 until sourceMessages.length()) {
            val message = sourceMessages.optJSONObject(index) ?: continue
            val role = message.optString("role")
            val text = valueText(message.opt("content"))
            if (role == "system") {
                if (text.isNotBlank()) systemText.append(text)
                continue
            }
            val anthropicRole = if (role == "assistant") "assistant" else "user"
            val content = JSONArray()
            if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))
            message.optJSONArray("tool_calls")?.let { calls ->
                for (callIndex in 0 until calls.length()) {
                    val call = calls.optJSONObject(callIndex) ?: continue
                    val function = call.optJSONObject("function") ?: JSONObject()
                    content.put(
                        JSONObject().put("type", "tool_use")
                            .put("id", call.optString("id").ifBlank { "tool_${UUID.randomUUID()}" })
                            .put("name", function.optString("name"))
                            .put("input", runCatching { JSONObject(function.optString("arguments", "{}")) }.getOrDefault(JSONObject())),
                    )
                }
            }
            if (role == "tool") {
                val result = JSONArray().put(
                    JSONObject().put("type", "tool_result")
                        .put("tool_use_id", message.optString("tool_call_id"))
                        .put("content", text),
                )
                messages.put(JSONObject().put("role", "user").put("content", result))
                continue
            }
            if (content.length() == 0) content.put(JSONObject().put("type", "text").put("text", ""))
            messages.put(JSONObject().put("role", anthropicRole).put("content", content))
        }
        if (systemText.isNotBlank()) target.put("system", systemText.toString())
        target.put("messages", messages)
        source.optJSONArray("tools")?.let { tools ->
            val converted = JSONArray()
            for (index in 0 until tools.length()) {
                val tool = tools.getJSONObject(index).optJSONObject("function") ?: continue
                converted.put(
                    JSONObject().put("name", tool.optString("name"))
                        .put("description", tool.optString("description"))
                        .put("input_schema", tool.optJSONObject("parameters") ?: JSONObject().put("type", "object")),
                )
            }
            target.put("tools", converted)
        }
        return target
    }

    // ── Streaming synthesis (client-facing, after an upstream success) ────────

    /** Anthropic SSE frames for a completed [message]. */
    fun anthropicSse(message: JSONObject): List<String> {
        val frames = mutableListOf<String>()
        fun event(name: String, data: JSONObject) {
            frames += "event: $name\ndata: $data\n\n"
        }
        val content = message.optJSONArray("content") ?: JSONArray()
        val startMessage = JSONObject(message.toString()).put("content", JSONArray()).put("stop_reason", JSONObject.NULL)
        event("message_start", JSONObject().put("type", "message_start").put("message", startMessage))
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            val type = block.optString("type")
            val start = if (type == "text") {
                JSONObject().put("type", "text").put("text", "")
            } else {
                JSONObject().put("type", "tool_use").put("id", block.optString("id")).put("name", block.optString("name")).put("input", JSONObject())
            }
            event("content_block_start", JSONObject().put("type", "content_block_start").put("index", index).put("content_block", start))
            val delta = if (type == "text") {
                JSONObject().put("type", "text_delta").put("text", block.optString("text"))
            } else {
                JSONObject().put("type", "input_json_delta").put("partial_json", block.optJSONObject("input")?.toString() ?: "{}")
            }
            event("content_block_delta", JSONObject().put("type", "content_block_delta").put("index", index).put("delta", delta))
            event("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", index))
        }
        event(
            "message_delta",
            JSONObject().put("type", "message_delta")
                .put("delta", JSONObject().put("stop_reason", message.optString("stop_reason", "end_turn")).put("stop_sequence", JSONObject.NULL))
                .put("usage", message.optJSONObject("usage") ?: JSONObject()),
        )
        event("message_stop", JSONObject().put("type", "message_stop"))
        return frames
    }

    /** OpenAI `chat.completion.chunk` frames for a completed OpenAI response body. */
    fun openAiSse(openAiBody: JSONObject): List<String> {
        val frames = mutableListOf<String>()
        val id = openAiBody.optString("id").ifBlank { "chatcmpl_${UUID.randomUUID()}" }
        val model = openAiBody.optString("model")
        val message = openAiBody.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: JSONObject()
        fun chunk(delta: JSONObject, finish: String?) {
            val choice = JSONObject().put("index", 0).put("delta", delta)
            if (finish != null) choice.put("finish_reason", finish)
            frames += "data: " + JSONObject()
                .put("id", id).put("object", "chat.completion.chunk").put("model", model)
                .put("choices", JSONArray().put(choice)) + "\n\n"
        }
        chunk(JSONObject().put("role", "assistant"), null)
        val text = message.optString("content")
        if (text.isNotBlank()) chunk(JSONObject().put("content", text), null)
        val calls = dedupeToolCalls(message.optJSONArray("tool_calls"))
        for (index in 0 until calls.length()) {
            chunk(JSONObject().put("tool_calls", JSONArray().put(calls.getJSONObject(index))), null)
        }
        chunk(JSONObject(), if (calls.length() > 0) "tool_calls" else "stop")
        frames += "data: [DONE]\n\n"
        return frames
    }

    /**
     * Removes duplicate tool calls from a single assistant message.
     *
     * A retried attempt must never emit the same tool call twice: the second copy is dropped
     * while preserving first-seen order, so a client executes each tool exactly once.
     */
    fun dedupeToolCalls(calls: JSONArray?): JSONArray {
        val result = JSONArray()
        if (calls == null) return result
        val seenIds = mutableSetOf<String>()
        val seenAnonymous = mutableSetOf<String>()
        for (index in 0 until calls.length()) {
            val call = calls.optJSONObject(index) ?: continue
            val id = call.optString("id")
            if (id.isNotBlank()) {
                if (!seenIds.add(id)) continue
            } else {
                val signature = call.optJSONObject("function")?.optString("name").orEmpty()
                if (signature.isNotBlank() && !seenAnonymous.add(signature)) continue
            }
            result.put(call)
        }
        return result
    }

    private fun contentText(array: JSONArray): String = buildString {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("type") == "text") append(item.optString("text"))
        }
    }

    private fun valueText(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> contentText(value).ifBlank { value.toString() }
        null, JSONObject.NULL -> ""
        else -> value.toString()
    }

    private fun normalizeModel(model: String): String = model
        .removePrefix("models/")
        .removePrefix("anthropic/")
        .substringBefore('[')
        .trim()
}
