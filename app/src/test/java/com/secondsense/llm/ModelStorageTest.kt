package com.secondsense.llm

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStorageTest {

    @Test
    fun isSafeModelFileName_acceptsExpectedName() {
        assertTrue(ModelStorage.isSafeModelFileName("gemma-3-1b-it-q5_k_m.gguf"))
    }

    @Test
    fun isSafeModelFileName_rejectsTraversalAndSlash() {
        assertFalse(ModelStorage.isSafeModelFileName("../model.gguf"))
        assertFalse(ModelStorage.isSafeModelFileName("folder/model.gguf"))
        assertFalse(ModelStorage.isSafeModelFileName("folder\\model.gguf"))
        assertFalse(ModelStorage.isSafeModelFileName(""))
    }

    @Test
    fun preferredVariant_roundTripsMarkerFile() {
        val tempDir = Files.createTempDirectory("secondsense-model-storage").toFile()
        val storage = ModelStorage(tempDir)

        storage.writePreferredVariant(LocalModelVariant.GEMMA_3_1B_IT_Q5_K_M)
        val restored = storage.readPreferredVariant()

        assertEquals(LocalModelVariant.GEMMA_3_1B_IT_Q5_K_M, restored)
    }
}
