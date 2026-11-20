package com.jarvis.assistant.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelFetcherTest {

    @Test
    fun freeDetectionUsesPricingWhenPresent() {
        assertTrue(ProviderModelFetcher.isFreeModel("x", 0.0, 0.0))
        assertFalse(ProviderModelFetcher.isFreeModel("x", 0.5, 1.5))
        assertTrue(ProviderModelFetcher.isFreeModel("x", 0.0, 0.0))
    }

    @Test
    fun freeDetectionFallsBackToIdConventionAndCuratedList() {
        assertTrue(ProviderModelFetcher.isFreeModel("meta/llama:free", null, null))
        assertFalse(ProviderModelFetcher.isFreeModel("meta/llama", null, null))
        assertTrue(ProviderModelFetcher.isFreeModel("meta/llama", null, null, setOf("meta/llama")))
    }

    @Test
    fun parsesOpenAiStyleModelsAndMarksFree() {
        val body = """
            {"data":[
              {"id":"free-model","pricing":{"prompt":"0","completion":"0"}},
              {"id":"paid-model","pricing":{"prompt":"0.000003","completion":"0.000006"}}
            ]}
        """.trimIndent()
        val models = ProviderModelFetcher.parseModels(body)
        assertEquals(2, models.size)
        assertTrue(models.first { it.id == "free-model" }.isFree)
        assertFalse(models.first { it.id == "paid-model" }.isFree)
    }
}
