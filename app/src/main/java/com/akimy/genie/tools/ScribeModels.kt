package com.akimy.genie.tools

import kotlinx.serialization.Serializable

@Serializable
data class ScribeConfig(
    val inputLanguage: String = "en-US",
    val outputLanguage: String = "en",
    val mode: ScribeMode = ScribeMode.GENERAL,
)

enum class ScribeMode {
    GENERAL,
    DOCTOR_SCRIBE
}

@Serializable
data class TranscriptionResult(
    val transcribedText: String,
    val language: String,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class GeneralInsights(
    val summary: String,
    val keyPoints: List<String>,
    val actionItems: List<String>,
    val transcription: String,
)

@Serializable
data class SoapNote(
    val subjective: String,
    val objective: String,
    val assessment: String,
    val plan: String,
    val transcription: String,
)

sealed class ScribeResult {
    data class General(val insights: GeneralInsights) : ScribeResult()
    data class Medical(val soapNote: SoapNote) : ScribeResult()
    data class Error(val message: String) : ScribeResult()
}

object ScribeSessionStore {
    private var currentConfig: ScribeConfig? = null
    private var currentResult: ScribeResult? = null
    private var audioFilePath: String? = null

    fun setConfig(config: ScribeConfig) {
        currentConfig = config
    }

    fun getConfig(): ScribeConfig? = currentConfig

    fun setAudioFilePath(path: String) {
        audioFilePath = path
    }

    fun getAudioFilePath(): String? = audioFilePath

    fun setResult(result: ScribeResult) {
        currentResult = result
    }

    fun getResult(): ScribeResult? = currentResult

    fun clear() {
        currentConfig = null
        currentResult = null
        audioFilePath = null
    }
}
