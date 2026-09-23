package com.apps.naviai.core.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEndpointTest {

    @Test
    fun `recognizes loopback hosts as local`() {
        assertTrue(LocalEndpoint.isLocal("http://localhost:11434/v1"))
        assertTrue(LocalEndpoint.isLocal("http://127.0.0.1:11434/v1"))
        assertTrue(LocalEndpoint.isLocal("http://[::1]:11434/v1"))
    }

    @Test
    fun `recognizes private LAN ranges as local`() {
        assertTrue(LocalEndpoint.isLocal("http://192.168.1.20:11434/v1"))
        assertTrue(LocalEndpoint.isLocal("http://10.0.0.5:11434/v1"))
        assertTrue(LocalEndpoint.isLocal("http://172.16.0.1:11434/v1"))
        assertTrue(LocalEndpoint.isLocal("http://172.31.255.255:11434/v1"))
    }

    @Test
    fun `does not treat adjacent 172 ranges as private`() {
        assertFalse(LocalEndpoint.isLocal("http://172.15.0.1:11434/v1"))
        assertFalse(LocalEndpoint.isLocal("http://172.32.0.1:11434/v1"))
    }

    @Test
    fun `treats hosted providers as remote`() {
        assertFalse(LocalEndpoint.isLocal("https://openrouter.ai/api/v1"))
        assertFalse(LocalEndpoint.isLocal("https://api.groq.com/openai/v1"))
        assertFalse(LocalEndpoint.isLocal("https://8.8.8.8/v1"))
    }

    @Test
    fun `does not resolve LAN hostnames -- treated as remote`() {
        assertFalse(LocalEndpoint.isLocal("http://ollama.local:11434/v1"))
    }

    @Test
    fun `handles blank or unparsable input without throwing`() {
        assertFalse(LocalEndpoint.isLocal(""))
        assertFalse(LocalEndpoint.isLocal("not a url"))
    }
}
