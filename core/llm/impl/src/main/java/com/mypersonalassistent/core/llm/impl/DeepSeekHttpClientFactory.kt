package com.mypersonalassistent.core.llm.impl

import android.os.SystemClock
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** Builds the only production Ktor client used by the DeepSeek adapter. */
internal class DeepSeekHttpClientFactory(
    private val debugLoggingEnabled: Boolean = BuildConfig.DEBUG,
    private val sink: NetworkLogSink = AndroidNetworkLogSink,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime
) {
    fun create(): HttpClient = configure(HttpClient(OkHttp) { configureBase() })

    internal fun create(engine: HttpClientEngine): HttpClient =
        configure(HttpClient(engine) { configureBase() })

    private fun io.ktor.client.HttpClientConfig<*>.configureBase() {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 120_000
            socketTimeoutMillis = 120_000
        }
    }

    private fun configure(client: HttpClient): HttpClient = client.apply {
        if (debugLoggingEnabled) {
            val logger = SafeNetworkLogger(sink, elapsedRealtimeMillis)
            plugin(HttpSend).intercept { request ->
                val context = request.attributes.getOrNull(NetworkLogContextKey)
                    ?: logger.start(request).also { request.attributes.put(NetworkLogContextKey, it) }
                try {
                    execute(request).also { call -> logger.complete(context, call.response.status.value) }
                } catch (cancelled: CancellationException) {
                    logger.failed(context, "cancelled")
                    throw cancelled
                } catch (failure: Throwable) {
                    logger.failed(context, failure.category())
                    throw failure
                }
            }
        }
    }
}

internal fun interface NetworkLogSink {
    fun log(message: String)
}

private object AndroidNetworkLogSink : NetworkLogSink {
    override fun log(message: String) {
        Log.d(TAG, message)
    }
}

private data class NetworkLogContext(val id: Long, val startedAtMillis: Long)

private val NetworkLogContextKey = AttributeKey<NetworkLogContext>("NetworkLogContext")

private class SafeNetworkLogger(
    private val sink: NetworkLogSink,
    private val elapsedRealtimeMillis: () -> Long
) {
    private val ids = AtomicLong(0)

    fun start(request: HttpRequestBuilder): NetworkLogContext {
        val context = NetworkLogContext(ids.incrementAndGet(), elapsedRealtimeMillis.safelyAt(0))
        safely { "[${context.id}] --> ${request.method.value} ${request.safeHostAndPath()}" }
        return context
    }

    fun complete(context: NetworkLogContext, status: Int) {
        safely { "[${context.id}] <-- $status ${duration(context)}ms" }
    }

    fun failed(context: NetworkLogContext, category: String) {
        safely { "[${context.id}] !! $category ${duration(context)}ms" }
    }

    private fun duration(context: NetworkLogContext): Long =
        (elapsedRealtimeMillis.safelyAt(context.startedAtMillis) - context.startedAtMillis).coerceAtLeast(0)

    private inline fun safely(message: () -> String) {
        runCatching { sink.log(message()) }
    }
}

private fun (() -> Long).safelyAt(fallback: Long): Long = runCatching { invoke() }.getOrDefault(fallback)

private fun HttpRequestBuilder.safeHostAndPath(): String {
    val path = url.build().encodedPath.ifEmpty { "/" }
    return "${url.host}$path"
}

private fun Throwable.category(): String = when (this) {
    is SocketTimeoutException -> "timeout"
    is IOException -> "transport"
    else -> "failure"
}

private const val TAG = "Network"
