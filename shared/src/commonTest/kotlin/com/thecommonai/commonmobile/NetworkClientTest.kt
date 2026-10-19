package com.thecommonai.commonmobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NetworkClientTest {
    @Test
    fun validatesSecureGatewayAndRemovesTrailingSlash() {
        assertEquals(
            "https://gateway.example",
            NetworkClient.validateGateway("  https://gateway.example/  "),
        )
    }

    @Test
    fun allowsLocalHttpGateway() {
        assertEquals(
            "http://localhost:8000",
            NetworkClient.validateGateway("http://localhost:8000/"),
        )
    }

    @Test
    fun rejectsRemoteHttpGateway() {
        assertFailsWith<IllegalArgumentException> {
            NetworkClient.validateGateway("http://gateway.example")
        }
    }

    @Test
    fun rejectsGatewayCredentialsAndQueryParameters() {
        assertFailsWith<IllegalArgumentException> {
            NetworkClient.validateGateway("https://user:password@gateway.example")
        }
        assertFailsWith<IllegalArgumentException> {
            NetworkClient.validateGateway("https://gateway.example?token=secret")
        }
        assertFailsWith<IllegalArgumentException> {
            NetworkClient.validateGateway("https://gateway.example?")
        }
        assertFailsWith<IllegalArgumentException> {
            NetworkClient.validateGateway("https://gateway.example#")
        }
    }
}
