package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import platform.Foundation.NSHTTPCookieAcceptPolicyNever
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData

internal actual fun createProxyHttpClient(
    subscriptionProxy: SubscriptionFetchProxy?,
    connectTimeoutMs: Long,
    requestTimeoutMs: Long,
    socketTimeoutMs: Long
): HttpClient {
    return HttpClient(Darwin) {
        expectSuccess = false

        engine {
            configureSession {
                // A subscription fetch must be stateless, as on Android (OkHttp without a cookie jar).
                // NSURLSession's defaults keep cookies and cache responses: once a panel set its browser
                // session cookie (the subscription page / logo lookup is fetched with a browser UA), every
                // later request carried it and the panel answered all of them with its HTML page.
                setHTTPShouldSetCookies(false)
                setHTTPCookieAcceptPolicy(NSHTTPCookieAcceptPolicyNever)
                setHTTPCookieStorage(null)
                setURLCache(null)
                setRequestCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
            }
        }

        install(HttpTimeout) {
            connectTimeoutMillis = connectTimeoutMs
            requestTimeoutMillis = requestTimeoutMs
            socketTimeoutMillis = socketTimeoutMs
        }
    }
}

internal actual suspend fun <T> withProxyAuthentication(
    subscriptionProxy: SubscriptionFetchProxy?,
    block: suspend () -> T
): T = block()
