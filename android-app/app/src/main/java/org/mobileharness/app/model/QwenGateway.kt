package org.mobileharness.app.model

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.mobileharness.app.core.GuiContext
import org.mobileharness.app.core.GuiReply
import org.mobileharness.app.core.ModelGateway
import org.mobileharness.app.core.ModelProfile
import org.mobileharness.app.core.PlannerContext
import org.mobileharness.app.core.PlannerDecision
import org.mobileharness.app.core.RunFailure
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class QwenGateway internal constructor(
    assetReader: (String) -> String,
    private val transport: ModelTransport,
    automaticAssetReader: ((String) -> String)? = null,
    private val keyResolver: (ModelProfile) -> String = { it.apiKey },
) : ModelGateway {
    constructor(context: Context) : this(context, { it.apiKey })

    constructor(context: Context, keyResolver: (ModelProfile) -> String) : this(
        { name -> context.applicationContext.assets.open(PromptBundles.assetPath("1.1.0", name)).bufferedReader(Charsets.UTF_8).use { it.readText() } },
        OkHttpModelTransport(),
        { name -> context.applicationContext.assets.open(PromptBundles.assetPath("1.2.0", name)).bufferedReader(Charsets.UTF_8).use { it.readText() } },
        keyResolver = keyResolver,
    )

    private val assembler = ModelRequestAssembler(assetReader)
    private val automaticAssembler by lazy {
        automaticAssetReader?.let { reader -> ModelRequestAssembler(reader).also {
            require(it.executionTools == assembler.executionTools && it.observationTools == assembler.observationTools)
        } }
    }
    private val protocol = QwenProtocol(assembler.executionTools, assembler.observationTools)
    /** Debug inspection receives only fixed enums/counts, never response text or arguments. */
    internal var protocolDiagnosticObserver: ((ProtocolDiagnostic) -> Unit)? = null
    private val lock = Any()
    private var generation = 0L
    private var prepared: Prepared? = null
    private val jobs = HashSet<Job>()
    private class Prepared(val context: Any, val generation: Long, val endpoint: String, val key: String, val body: ByteArray)

    override fun preflightGui(context: GuiContext) = synchronized(lock) {
        forgetPrepared()
        prepared = prepareGui(context)
    }

    override fun preflightPlanner(context: PlannerContext) = synchronized(lock) {
        forgetPrepared()
        prepared = preparePlanner(context)
    }

    override suspend fun gui(context: GuiContext): GuiReply {
        val request = synchronized(lock) {
            if (prepared?.context === context) prepared!!.also { prepared = null }
            else { forgetPrepared(); prepareGui(context) }
        }
        return execute(request) { response ->
            val text = decode(response)
            val allowAuxiliaryText = context.options.adapterVersion in setOf("1.3.0", "1.4.0")
            protocolDiagnosticObserver?.let { observer ->
                val diagnostic = protocol.inspectGui(text, context.phase, allowAuxiliaryText = allowAuxiliaryText)
                runCatching { observer(diagnostic) }
            }
            protocol.gui(text, context.phase, allowAuxiliaryText = allowAuxiliaryText)
        }
    }

    override suspend fun planner(context: PlannerContext): PlannerDecision {
        val request = synchronized(lock) {
            if (prepared?.context === context) prepared!!.also { prepared = null }
            else { forgetPrepared(); preparePlanner(context) }
        }
        return execute(request) { response ->
            val text = decode(response)
            protocolDiagnosticObserver?.let { observer ->
                val diagnostic = protocol.inspectPlanner(text, context.callIndex)
                runCatching { observer(diagnostic) }
            }
            protocol.planner(text, context.callIndex)
        }
    }

    private fun prepareGui(context: GuiContext): Prepared {
        val url = endpoint(context.options.gui.baseUrl)
        val key = resolveKey(context.options.gui)
        return Prepared(context, generation, url, key, assembler(context.options.promptBundleVersion).gui(context))
    }

    private fun preparePlanner(context: PlannerContext): Prepared {
        val url = endpoint(context.options.planner.baseUrl)
        val key = resolveKey(context.options.planner)
        return Prepared(context, generation, url, key, assembler(context.options.promptBundleVersion).planner(context))
    }

    private fun assembler(version: String): ModelRequestAssembler = when (version) {
        "1.1.0" -> assembler
        "1.2.0" -> automaticAssembler ?: throw RunFailure("COORDINATOR", "INVALID_STATE")
        else -> throw RunFailure("COORDINATOR", "INVALID_STATE")
    }

    private suspend fun <T> execute(request: Prepared, parse: (ByteArray) -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        try {
            synchronized(lock) {
                if (request.generation != generation) throw CancellationException()
                jobs.add(job)
            }
            currentCoroutineContext().ensureActive()
            val response = transport.exchange(request.endpoint, request.key, request.body)
            try { currentCoroutineContext().ensureActive(); parse(response) } finally { response.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: RunFailure) { throw failure
        } catch (_: Exception) { throw RunFailure("MODEL", "TRANSPORT_ERROR")
        } finally {
            request.body.fill(0)
            synchronized(lock) { jobs.remove(job) }
        }
    }

    override fun cancelAll() {
        synchronized(lock) {
            generation++
            forgetPrepared()
            jobs.toList().forEach { it.cancel() }
        }
        transport.cancelAll()
    }

    private fun forgetPrepared() { prepared?.body?.fill(0); prepared = null }

    private fun resolveKey(profile: ModelProfile): String = try {
        keyResolver(profile).also { validateKey(it) }
    } catch (_: Exception) { throw RunFailure("COORDINATOR", "INVALID_STATE") }

    private fun validateKey(key: String) {
        if (key.isBlank() || key.any { it <= ' ' || it >= '\u007f' }) throw RunFailure("COORDINATOR", "INVALID_STATE")
    }

    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { throw RunFailure("MODEL", "INVALID_OUTPUT", protocolReason = "RESPONSE_UTF8_INVALID") }

    internal companion object {
        fun endpoint(value: String): String {
            val parsed = value.toHttpUrlOrNull() ?: throw RunFailure("COORDINATOR", "INVALID_STATE")
            val hasUserInfo = try { URI(value).rawUserInfo != null } catch (_: Exception) { true }
            if (hasUserInfo || parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null) {
                throw RunFailure("COORDINATOR", "INVALID_STATE")
            }
            val path = parsed.encodedPath.trimEnd('/')
            val normalized = if (path.endsWith("/chat/completions")) path else "$path/chat/completions"
            return parsed.newBuilder().encodedPath(normalized).build().toString()
        }
    }
}

internal interface ModelTransport {
    suspend fun exchange(endpoint: String, key: String, body: ByteArray): ByteArray
    fun cancelAll()
}

internal class RequestReplayBlockedException : IOException()

/** Classification uses only exception types, never messages, URLs, headers or raw causes. */
internal fun transportFailure(error: IOException): RunFailure = RunFailure("MODEL", "TRANSPORT_ERROR", transportReason = when (error) {
    is UnknownHostException -> "DNS_FAILURE"
    is SSLHandshakeException -> "TLS_HANDSHAKE_FAILED"
    is SSLPeerUnverifiedException -> "TLS_PEER_UNVERIFIED"
    is SocketTimeoutException -> "TIMEOUT"
    is ConnectException -> "CONNECT_FAILED"
    is RequestReplayBlockedException -> "REQUEST_REPLAY_BLOCKED"
    else -> "NETWORK_IO"
})

/** Also blocks OkHttp's 503/421 follow-ups, which are independent of its connection-retry flag. */
internal class OneShotJsonBody(private val bytes: ByteArray) : RequestBody() {
    private val written = AtomicBoolean()
    override fun contentType() = "application/json; charset=utf-8".toMediaType()
    override fun contentLength() = bytes.size.toLong()
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) {
        if (!written.compareAndSet(false, true)) throw RequestReplayBlockedException()
        sink.write(bytes)
    }
}

internal class OkHttpModelTransport : ModelTransport {
    internal val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .cache(null).connectTimeout(15, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
    private val active = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())

    override suspend fun exchange(endpoint: String, key: String, body: ByteArray): ByteArray = suspendCancellableCoroutine { continuation ->
        val request = try { Request.Builder().url(endpoint).header("Authorization", "Bearer $key")
            .post(OneShotJsonBody(body)).build() }
        catch (_: Exception) { continuation.resumeWithException(RunFailure("COORDINATOR", "INVALID_STATE")); return@suspendCancellableCoroutine }
        val call = client.newCall(request)
        active.add(call)
        continuation.invokeOnCancellation { call.cancel() }
        if (!continuation.isActive) { active.remove(call); call.cancel(); return@suspendCancellableCoroutine }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                active.remove(call)
                if (continuation.isActive) continuation.resumeWithException(transportFailure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use { value ->
                        if (!value.isSuccessful) throw RunFailure("MODEL", "API_ERROR", httpStatus = value.code)
                        val responseBody = value.body ?: throw RunFailure("MODEL", "INVALID_OUTPUT", protocolReason = "RESPONSE_BODY_MISSING")
                        // Response bodies never enter a disk cache or diagnostic logger.
                        val source = responseBody.source()
                        if (source.request(8_000_001L)) throw RunFailure("MODEL", "INVALID_OUTPUT", protocolReason = "RESPONSE_TOO_LARGE")
                        source.readByteArray()
                    }
                    if (continuation.isActive) continuation.resume(bytes) else bytes.fill(0)
                } catch (failure: Exception) {
                    val safe = when (failure) {
                        is RunFailure -> failure
                        is IOException -> transportFailure(failure)
                        else -> RunFailure("MODEL", "TRANSPORT_ERROR")
                    }
                    if (continuation.isActive) continuation.resumeWithException(safe)
                } finally { active.remove(call) }
            }
        })
    }

    override fun cancelAll() { active.toList().forEach { it.cancel() } }
}
