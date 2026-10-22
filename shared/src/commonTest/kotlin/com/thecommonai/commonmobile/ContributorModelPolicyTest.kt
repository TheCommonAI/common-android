package com.thecommonai.commonmobile

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ContributorModelPolicyTest {
    private val capabilities = listOf(
        "gguf:q2_0",
        "gguf:q2_0_g64",
        "ternary:hadamard-activations",
    )

    @Test
    fun acceptsCatalogueFormatsForSupportedBonsaiModels() {
        listOf("bonsai2-2b", "bonsai2-4b", "bonsai2-9b").forEach { id ->
            ContributorModelPolicy.validate(
                id,
                androidSupported = true,
                modelFormat = "Q2_0",
                requirements = listOf("gguf:q2_0", "ternary:hadamard-activations"),
                runtimeCapabilities = capabilities,
            )
        }
        ContributorModelPolicy.validate(
            "bonsai-8b",
            androidSupported = true,
            modelFormat = "Q2_0_g64",
            requirements = listOf("gguf:q2_0_g64"),
            runtimeCapabilities = capabilities,
        )
    }

    @Test
    fun rejectsUnsupportedIdsAndFormatSubstitution() {
        assertFailsWith<IllegalArgumentException> {
            ContributorModelPolicy.validate(
                "ordinary-ollama-model",
                androidSupported = true,
                modelFormat = "Q2_0",
                requirements = listOf("gguf:q2_0"),
                runtimeCapabilities = capabilities,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ContributorModelPolicy.validate(
                "bonsai-8b",
                androidSupported = true,
                modelFormat = "Q2_0",
                requirements = listOf("gguf:q2_0_g64"),
                runtimeCapabilities = capabilities,
            )
        }
    }

    @Test
    fun rejectsUnlistedModelsAndMissingRuntimeRequirements() {
        assertFailsWith<IllegalArgumentException> {
            ContributorModelPolicy.validate(
                "bonsai2-2b",
                androidSupported = false,
                modelFormat = "Q2_0",
                requirements = listOf("gguf:q2_0", "ternary:hadamard-activations"),
                runtimeCapabilities = capabilities,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ContributorModelPolicy.validate(
                "bonsai2-9b",
                androidSupported = true,
                modelFormat = "Q2_0",
                requirements = listOf("gguf:q2_0", "ternary:hadamard-activations"),
                runtimeCapabilities = listOf("gguf:q2_0"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ContributorModelPolicy.validate(
                "bonsai2-4b",
                androidSupported = true,
                modelFormat = "Q2_0",
                requirements = listOf("gguf:q2_0"),
                runtimeCapabilities = capabilities,
            )
        }
    }
}
