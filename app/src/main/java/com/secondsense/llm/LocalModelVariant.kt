package com.secondsense.llm

enum class LocalModelVariant(
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedSha256: String
) {
    GEMMA_3_1B_IT_Q5_K_M(
        displayName = "google/gemma-3-1b-it (Q5_K_M GGUF)",
        fileName = "gemma-3-1b-it-q5_k_m.gguf",
        downloadUrl = "https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF/resolve/main/google_gemma-3-1b-it-Q5_K_M.gguf?download=true",
        expectedSha256 = "59a10a3c8dc8a9c0bda2c8882198073b1cfebbb2b443aa2fc4cfca4f92eeb805"
    );

    fun fallbackVariant(): LocalModelVariant {
        return GEMMA_3_1B_IT_Q5_K_M
    }

    companion object {
        fun preferredDefault(): LocalModelVariant = GEMMA_3_1B_IT_Q5_K_M
    }
}
