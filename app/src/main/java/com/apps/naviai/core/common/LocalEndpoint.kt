package com.apps.naviai.core.common

import java.net.URI

/**
 * Best-effort check for whether [baseUrl] points at a server reachable
 * without going out to the internet (loopback, or a private/link-local LAN
 * address -- e.g. a local Ollama/LM Studio instance) versus a public host.
 * Used by Offline Mode to decide whether the user-configured LLM endpoint
 * (see llm/LlmClient.kt) still counts as "offline" -- a call to
 * `http://127.0.0.1:11434` or `http://192.168.1.20:11434` never leaves the
 * device/LAN, unlike a call to a hosted provider.
 *
 * Pure string matching against the literal host -- deliberately does NOT
 * resolve hostnames via DNS (that's itself a network call, and would need
 * to be threaded off the caller's dispatcher for no real benefit here).
 * A LAN mDNS name like "ollama.local" is therefore treated as remote; use
 * a literal IP or "localhost" to be recognized. Any host that can't be
 * parsed is also treated as remote -- a false "this is local" would let
 * Offline Mode silently make a real network call.
 */
object LocalEndpoint {

    private val PRIVATE_IPV4_PREFIXES = listOf("127.", "10.", "192.168.")
    private val PRIVATE_IPV4_172_RANGE = 16..31

    fun isLocal(baseUrl: String): Boolean {
        val host = runCatching { URI(baseUrl.trim()).host }.getOrNull()?.lowercase()?.trim('[', ']') ?: return false
        if (host.isBlank()) return false

        if (host == "localhost" || host == "::1") return true
        if (PRIVATE_IPV4_PREFIXES.any { host.startsWith(it) }) return true

        val octets = host.split(".")
        if (octets.size == 4 && octets[0] == "172") {
            val second = octets[1].toIntOrNull() ?: return false
            return second in PRIVATE_IPV4_172_RANGE
        }

        return false
    }
}
