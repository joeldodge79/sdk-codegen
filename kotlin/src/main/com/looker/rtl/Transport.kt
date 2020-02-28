/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Looker Data Sciences, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.looker.rtl

import io.ktor.client.HttpClient
import io.ktor.client.call.call
import io.ktor.client.call.receive
import io.ktor.client.engine.apache.Apache
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.json.defaultSerializer
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.response.HttpResponse
import io.ktor.http.takeFrom
import kotlinx.coroutines.runBlocking
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.conn.ssl.TrustSelfSignedStrategy
import org.apache.http.ssl.SSLContextBuilder
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
import java.util.*

sealed class SDKResponse {
    /** A successful SDK call. */
    data class SDKSuccessResponse<T>(
            /** The object returned by the SDK call. */
            val value: T
    ) : SDKResponse() {
        /** Whether the SDK call was successful. */
        val ok: Boolean = true
    }

    /** An erroring SDK call. */
    data class SDKErrorResponse<T>(
            /** The error object returned by the SDK call. */
            val value: T
    ) : SDKResponse() {
        /** Whether the SDK call was successful. */
        val ok: Boolean = false
    }

    /** An error representing an issue in the SDK, like a network or parsing error. */
    data class SDKError(val message: String) : SDKResponse() {
        val type: String = "sdk_error"
    }
}

/**
 * Response handler that throws an error on error response, returns success result on success
 */
fun <T> ok(response: SDKResponse): T {
    @Suppress("UNCHECKED_CAST")
    when (response) {
        is SDKResponse.SDKErrorResponse<*> -> throw Error(response.value.toString())
        is SDKResponse.SDKSuccessResponse<*> -> return response.value as T
        else -> throw Error("Fail!!")
    }
}

enum class HttpMethod(val value: io.ktor.http.HttpMethod) {
    GET(io.ktor.http.HttpMethod.Get),
    POST(io.ktor.http.HttpMethod.Post),
    PUT(io.ktor.http.HttpMethod.Put),
    DELETE(io.ktor.http.HttpMethod.Delete),
    PATCH(io.ktor.http.HttpMethod.Patch),
    HEAD(io.ktor.http.HttpMethod.Head)
    // TODO: Using the ktor-client-apache may support TRACE?
}

data class RequestSettings(
        val method: HttpMethod,
        val url: String,
        val headers: Map<String, String> = mapOf()
)

typealias Authenticator = (init: RequestSettings) -> RequestSettings

fun defaultAuthenticator(requestSettings: RequestSettings): RequestSettings = requestSettings

interface TransportOptions {
    var baseUrl: String
    var apiVersion: String
    var verifySSL: Boolean
    var timeout: Int
    var headers: Map<String, String>
}

interface ConfigurationProvider : TransportOptions {
    fun isConfigured(): Boolean
    fun readConfig(): Map<String, String>
}

data class TransportSettings(
        override var baseUrl: String = "",
        override var apiVersion: String = DEFAULT_API_VERSION,
        override var verifySSL: Boolean = true,
        override var timeout: Int = DEFAULT_TIMEOUT,
        override var headers: Map<String, String> = mapOf()
) : TransportOptions

private val utcFormat by lazy { DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'") }

fun encodeParam(value: Any?) : String {
    val utf8 = "utf-8"
    var encoded = if (value is ZonedDateTime) {
        value.toOffsetDateTime().format(utcFormat)
    } else {
        "$value"
    }
    val decoded = URLDecoder.decode(encoded, utf8)
    if (encoded == decoded) {
        encoded = URLEncoder.encode(encoded, utf8)
    }
    return encoded
}

fun encodeValues(params: Values = mapOf()): String {
    @Suppress("UNCHECKED_CAST")
    return params
            .filter { (_, v) -> v !== null }
            .map { (k, v) -> "$k=${encodeParam(v)}" }
            .joinToString("&")

}

fun addQueryParams(path: String, params: Values = mapOf()): String {
    if (params.isEmpty()) return path

    val qp = encodeValues(params)
    return "$path?$qp"
}

fun customClient(options: TransportOptions): HttpClient {
    // Timeout is passed in as seconds
    val timeout = options.timeout * 1000

    return HttpClient(Apache) {
        install(JsonFeature) {
            serializer = JacksonSerializer()
        }
        engine {
            customizeClient {
                if (!options.verifySSL) {
                    setSSLContext(
                            SSLContextBuilder
                                    .create()
                                    .loadTrustMaterial(TrustSelfSignedStrategy())
                                    .build()
                    )
                    setSSLHostnameVerifier(NoopHostnameVerifier())
                }
                connectTimeout = timeout
                connectionRequestTimeout = timeout
                socketTimeout = timeout
            }
        }
    }
}

class Transport(val options: TransportOptions) {

    private val apiPath = "${options.baseUrl}/api/${options.apiVersion}"

    private fun ok(res: HttpResponse): Boolean {
        // Thought: We should use whatever is idiomatic for Kotlin
        return (res.status.value >= 200) && (res.status.value <= 226)
    }

    /**
     * Create the correct http request path
     * @param path Relative or absolute path
     * @param queryParams query string arguments (if any)
     * @param authenticator optional authenticator callback for API requests
     * @return a fully qualified path that is the base url, the api path, or a pass through request url
     */
    fun makeUrl(
            path: String,
            queryParams: Values = mapOf(),
            authenticator: Authenticator? = null // TODO figure out why ::defaultAuthenticator is matching when it shouldn't
    ): String {
        return if (path.startsWith("http://", true)
                || path.startsWith("https://", true)) {
            "" // full path was passed in
        } else {
            if (authenticator === null) {
                options.baseUrl
            } else {
                apiPath
            }
        } + addQueryParams(path, queryParams)
    }

    inline fun <reified T> request(
            method: HttpMethod,
            path: String,
            queryParams: Values = mapOf(),
            body: Any? = null,
            noinline authenticator: Authenticator? = null): SDKResponse {
        // TODO get overrides parameter to work without causing compilation errors in UserSession
//            overrides: TransportOptions? = null): SDKResponse {

        val builder = httpRequestBuilder(method, path, queryParams, authenticator, body)

        val client = customClient(options)
        // TODO get overrides parameter working
//        overrides?.let { o ->
//            if (options.verifySSL != o.verifySSL || options.timeout != o.timeout) {
//                // need an HTTP client with custom options
//                client = customClient(o)
//            }
//        }

        val result = runBlocking {
            SDKResponse.SDKSuccessResponse(client.call(builder).response.receive<T>())
        }
        client.close()
        return result
    }

    fun httpRequestBuilder(method: HttpMethod, path: String, queryParams: Values, authenticator: Authenticator?, body: Any?): HttpRequestBuilder {
        val builder = HttpRequestBuilder()
        // Set the request method
        builder.method = method.value

        // Handle the headers
        val headers = options.headers.toMutableMap()
        headers["User-Agent"] = AGENT_TAG
        headers[LOOKER_APPID] = AGENT_TAG

        val requestPath = makeUrl(path, queryParams, authenticator)

        val auth = authenticator ?: ::defaultAuthenticator

        val finishedRequest = auth(RequestSettings(method, requestPath, headers))

        builder.method = finishedRequest.method.value
        finishedRequest.headers.forEach { (k, v) ->
            builder.headers.append(k, v)
        }
        builder.url.takeFrom(finishedRequest.url)

        if (body != null) {
            if (body is FormDataContent) {
                // Encoded form
                builder.body = body
            } else {
                // Request body
                val json = defaultSerializer()

                val jsonBody = json.write(body)
                builder.body = jsonBody  // TODO: I think having to do this is a bug? https://github.com/ktorio/ktor/issues/1265
                headers["Content-Length"] = jsonBody.contentLength.toString()
            }
        }
        return builder
    }

}
