package com.thecommonai.commonmobile

import kotlinx.coroutines.flow.StateFlow

data class ContributorCandidate(
    val id: String,
    val displayName: String,
    val modelFormat: String,
    val runtimeRequirements: List<String>,
    val runtimeNotes: String?,
    val reason: String,
)

data class ContributorStatus(
    val message: String = "Not contributing",
    val enrolled: Boolean = false,
    val connected: Boolean = false,
)

interface ContributorController {
    val status: StateFlow<ContributorStatus>

    suspend fun checkCompatibility(gateway: String): ContributorCandidate

    suspend fun start(gateway: String, candidate: ContributorCandidate)

    suspend fun stop()
}

object ContributorModelPolicy {
    private val formats = mapOf(
        "bonsai2-2b" to "Q2_0",
        "bonsai2-4b" to "Q2_0",
        "bonsai2-9b" to "Q2_0",
        "bonsai-8b" to "Q2_0_g64",
    )

    fun validate(
        catalogueId: String,
        androidSupported: Boolean,
        modelFormat: String?,
        requirements: List<String>,
        runtimeCapabilities: List<String>,
    ) {
        require(androidSupported) { "The network catalogue does not explicitly mark this model as Android-supported." }
        val expectedFormat = formats[catalogueId]
            ?: throw IllegalArgumentException("The network assigned an unsupported Android model ID: $catalogueId.")
        require(modelFormat == expectedFormat) {
            "The assigned model format is not supported for $catalogueId."
        }
        require(requirements.isNotEmpty() && requirements.all { it in runtimeCapabilities }) {
            "The selected model requires capabilities this Android runtime does not declare."
        }
        if (catalogueId.startsWith("bonsai2-")) {
            require("ternary:hadamard-activations" in requirements) {
                "Bonsai 2 requires the catalogue-declared ternary Hadamard activation runtime."
            }
        }
    }
}
