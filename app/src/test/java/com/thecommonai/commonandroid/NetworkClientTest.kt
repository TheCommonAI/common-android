package com.thecommonai.commonandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

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
        assertThrows(IllegalArgumentException::class.java) {
            NetworkClient.validateGateway("http://gateway.example")
        }
    }

    @Test
    fun rejectsGatewayCredentialsAndQueryParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkClient.validateGateway("https://user:secret@gateway.example")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkClient.validateGateway("https://gateway.example?token=secret")
        }
    }
}
