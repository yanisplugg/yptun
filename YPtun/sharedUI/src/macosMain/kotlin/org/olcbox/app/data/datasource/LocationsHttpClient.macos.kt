package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.cinterop.ExperimentalForeignApi
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust

@OptIn(ExperimentalForeignApi::class)
internal actual fun createProxyHttpClient(
    subscriptionProxy: SubscriptionFetchProxy?,
    connectTimeoutMs: Long,
    requestTimeoutMs: Long,
    socketTimeoutMs: Long
): HttpClient {
    return HttpClient(Darwin) {
        expectSuccess = false
        followRedirects = true

        engine {
            configureSession {
                setAllowsCellularAccess(true)
            }
            handleChallenge { _, _, challenge, completionHandler ->
                val serverTrust = challenge.protectionSpace.serverTrust
                if (challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust && serverTrust != null) {
                    completionHandler(
                        NSURLSessionAuthChallengeUseCredential,
                        NSURLCredential.credentialForTrust(serverTrust)
                    )
                } else {
                    completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
                }
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

