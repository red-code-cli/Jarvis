package com.jarvis.harness.gateway

import com.jarvis.harness.model.ProviderProfile
import com.jarvis.harness.network.LocalNetwork
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Supplies the ordered targets, their secrets and switch notifications to [LocalProviderGateway].
 * The host (Jarvis) implements this from the provider catalog + Keystore vault + priority order.
 */
interface GatewayRouteResolver {
    /** Every (provider, model, key) to try, in the user's priority order. */
    fun targets(): List<GatewayTarget>

    /** Plaintext secret for [target], or null when it is no longer available. */
    fun secret(target: GatewayTarget): String?

    /** A switch is about to happen from [from] to [to] after [failure]. Never receives secrets. */
    fun onSwitch(from: GatewayTarget, to: GatewayTarget, failure: GatewayFailure) {}

    /** [target] answered successfully; this is the active provider/model for the task. */
    fun onActive(target: GatewayTarget) {}
}

/** A running loopback gateway the agent bridges can point at. */
interface ActiveLocalGateway {
    val url: String

    /** Random per-run bearer token the agent must present; other apps on the phone cannot guess it. */
    val token: String

    /** Whether this gateway instance serves [profile] (so a bridge can route through it). */
    fun covers(profile: ProviderProfile): Boolean
}

/**
 * In-app, loopback-only, OpenAI- and Anthropic-compatible proxy.
 *
 * Agents point at [url] instead of calling providers directly. The gateway picks the next
 * healthy (provider, model, key) per the priority chain, and on 429/quota/401/403/5xx/
 * timeout/network error switches immediately and replays the *same* request. Upstream calls
 * are non-streaming, so failover always happens before a byte reaches the client; streaming
 * is synthesized after a success, which makes duplicate tool calls structurally impossible.
 *
 * Bound to 127.0.0.1 only and gated by a per-run bearer token, so no other app can spend the
 * user's keys through it.
 */
class LocalProviderGateway(
    private val resolver: GatewayRouteResolver,
    private val planner: FailoverPlanner = FailoverPlanner(),
) : ActiveLocalGateway, AutoCloseable {

    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    override val url: String = "http://127.0.0.1:${server.localPort}"
    override val token: String = java.util.UUID.randomUUID().toString().replace("-", "")

    /** True while the gateway serves this kind of provider. The default serves everything. */
    @Volatile
    var coversProvider: (ProviderProfile) -> Boolean = { true }

    override fun covers(profile: ProviderProfile): Boolean = runCatching { coversProvider(profile) }.getOrDefault(false)

    fun start(): LocalProviderGateway = apply {
        Thread({ acceptLoop() }, "jarvis-gateway").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            runCatching { server.accept() }.getOrNull()?.let { socket ->
                Thread({ socket.use(::handle) }, "jarvis-gateway-req").apply { isDaemon = true; start() }
            }
        }
    }

    private fun handle(socket: Socket) {
        runCatching { socket.soTimeout = SOCKET_TIMEOUT_MS }
        // Defence in depth: refuse anything that is not loopback, even though the socket
        // is already bound to 127.0.0.1.
        if (!runCatching { socket.inetAddress.isLoopbackAddress }.getOrDefault(false)) return
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?')
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        if (!isAuthorized(headers)) {
            writeJson(output, 401, errorJson("authentication_error", "Missing or invalid gateway token"))
            return
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_REQUEST_BYTES) {
            writeJson(output, 413, errorJson("invalid_request_error", "Request body is too large"))
            return
        }
        val bodyBytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bodyBytes, offset, length - offset)
            if (count < 0) break
            offset += count
        }
        val body = bodyBytes.decodeToString()

        when {
            path.endsWith("/health") -> writeJson(output, 200, healthJson())
            path.endsWith("/models") -> writeJson(output, 200, modelsJson())
            path.endsWith("/count_tokens") -> writeJson(output, 200, countTokensJson(body))
            path.endsWith("/messages") && method == "POST" -> dispatch(GatewayWire.ANTHROPIC, body, output)
            path.endsWith("/chat/completions") && method == "POST" -> dispatch(GatewayWire.OPENAI_CHAT, body, output)
            else -> writeJson(output, 404, errorJson("not_found", "Unsupported gateway endpoint"))
        }
    }

    private fun dispatch(clientWire: GatewayWire, body: String, output: BufferedOutputStream) {
        val clientBody = runCatching { JSONObject(body) }.getOrNull()
            ?: return writeJson(output, 400, errorJson("invalid_request_error", "Request body is not valid JSON"))
        val wantsStream = clientBody.optBoolean("stream", false)
        val now = System.currentTimeMillis()
        val targets = resolver.targets()
        if (targets.isEmpty()) {
            return writeJson(output, 503, errorJson("api_error", "No provider is configured for the coding engine"))
        }
        val ordered = planner.planWithFallback(targets, now)

        var last: GatewayFailure? = null
        ordered.forEachIndexed { index, target ->
            val secret = resolver.secret(target)
            if (secret.isNullOrBlank()) {
                last = GatewayFailure(null, false, "No key saved for ${target.providerName}", FailureClass.SWITCH)
                return@forEachIndexed
            }
            val upstreamBody = runCatching { GatewayRequestMapper.buildUpstreamBody(clientBody, clientWire, target) }
                .getOrElse { error ->
                    return writeJson(output, 400, errorJson("invalid_request_error", error.message ?: "Could not map request"))
                }
            val result = callUpstream(target, secret, upstreamBody)
            if (result.networkError || result.status !in 200..299) {
                val failure = planner.onFailure(target, result.status, result.networkError, result.body, now)
                last = failure
                val next = ordered.getOrNull(index + 1)?.takeIf { failure.failureClass == FailureClass.SWITCH }
                if (next != null) {
                    runCatching { resolver.onSwitch(target, next, failure) }
                } else if (failure.failureClass == FailureClass.FATAL) {
                    val status = result.status?.takeIf { it in 400..499 } ?: 400
                    return writeJson(output, status, upstreamErrorJson(failure))
                }
            } else {
                planner.onSuccess(target, now)
                runCatching { resolver.onActive(target) }
                val upstream = runCatching { JSONObject(result.body) }.getOrElse {
                    return writeJson(output, 502, errorJson("api_error", "Provider returned an unreadable response"))
                }
                val clientResponse = GatewayRequestMapper.buildClientBody(upstream, target.wire, clientWire, target.model)
                if (wantsStream) writeSse(output, clientWire, clientResponse)
                else writeJson(output, 200, clientResponse.toString())
                return
            }
        }
        writeJson(output, 502, upstreamErrorJson(last ?: GatewayFailure(null, true, "All providers failed", FailureClass.SWITCH)))
    }

    private data class UpstreamResult(val status: Int, val body: String, val networkError: Boolean)

    private fun callUpstream(target: GatewayTarget, secret: String, body: JSONObject): UpstreamResult {
        val endpoint = upstreamEndpoint(target)
        if (!LocalNetwork.isPlainHttpAllowed(endpoint)) {
            return UpstreamResult(0, "Plain http:// is only allowed for local network addresses", true)
        }
        return runCatching {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                when (target.wire) {
                    GatewayWire.OPENAI_CHAT -> connection.setRequestProperty("Authorization", "Bearer $secret")
                    GatewayWire.ANTHROPIC -> {
                        connection.setRequestProperty("x-api-key", secret)
                        connection.setRequestProperty("Authorization", "Bearer $secret")
                        connection.setRequestProperty("anthropic-version", "2023-06-01")
                    }
                }
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                UpstreamResult(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty(), false)
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error -> UpstreamResult(0, error.message ?: "Network error", true) }
    }

    private fun upstreamEndpoint(target: GatewayTarget): String {
        val base = target.baseUrl.trim().trimEnd('/')
        return when (target.wire) {
            GatewayWire.OPENAI_CHAT -> "$base/chat/completions"
            GatewayWire.ANTHROPIC -> if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        }
    }

    private fun writeSse(
        output: BufferedOutputStream,
        clientWire: GatewayWire,
        clientResponse: JSONObject,
    ) {
        output.write(
            "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        val frames = when (clientWire) {
            GatewayWire.ANTHROPIC -> GatewayRequestMapper.anthropicSse(clientResponse)
            GatewayWire.OPENAI_CHAT -> GatewayRequestMapper.openAiSse(clientResponse)
        }
        frames.forEach { output.write(it.toByteArray()) }
        output.flush()
    }

    private fun healthJson(): String {
        val now = System.currentTimeMillis()
        val array = JSONArray()
        resolver.targets().forEach { target ->
            val snapshot = planner.snapshot(target, now)
            array.put(
                JSONObject()
                    .put("provider", target.providerId)
                    .put("model", target.model)
                    .put("key", target.keyName)
                    .put("wire", target.wire.name.lowercase())
                    .put("health", planner.healthScore(target))
                    .put("breaker_open", snapshot.open)
                    .put("cooldown_ms", snapshot.cooldownRemainingMillis),
            )
        }
        return JSONObject().put("status", "ok").put("port", server.localPort).put("targets", array).toString()
    }

    private fun modelsJson(): String {
        val array = JSONArray()
        resolver.targets().map { it.model }.filter(String::isNotBlank).distinct().forEach { array.put(it) }
        return JSONObject().put("object", "list").put("data", array).toString()
    }

    private fun countTokensJson(body: String): String =
        JSONObject().put("input_tokens", body.length / 4 + 1).toString()

    private fun isAuthorized(headers: Map<String, String>): Boolean {
        val presented = headers["x-api-key"]
            ?: headers["authorization"]?.removePrefix("Bearer ")?.trim()
            ?: return false
        return java.security.MessageDigest.isEqual(presented.toByteArray(), token.toByteArray())
    }

    private fun writeJson(output: BufferedOutputStream, code: Int, body: String) {
        val bytes = body.toByteArray()
        val reason = if (code in 200..299) "OK" else "Error"
        output.write("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun errorJson(type: String, message: String): String =
        JSONObject().put("type", "error").put("error", JSONObject().put("type", type).put("message", message)).toString()

    private fun upstreamErrorJson(failure: GatewayFailure): String =
        JSONObject().put("type", "error").put(
            "error",
            JSONObject()
                .put("type", "api_error")
                .put("message", failure.message)
                .put("status", failure.status ?: 0),
        ).toString()

    override fun close() {
        running.set(false)
        runCatching { server.close() }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= MAX_HEADER_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString()
            if (value == '\n'.code) return bytes.toByteArray().decodeToString().trimEnd('\r')
            bytes += value.toByte()
        }
        return null
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 16 * 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 16 * 1024
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 180_000
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}
