package com.akimy.genie.tools

import kotlinx.serialization.Serializable

@Serializable
data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
)

@Serializable
data class QuizSession(
    val title: String,
    val questions: List<QuizQuestion>,
)

object QuizStore {
    @Volatile
    private var pendingSession: QuizSession? = null

    fun setPending(session: QuizSession) {
        pendingSession = session
    }

    fun consumePending(): QuizSession? {
        val session = pendingSession
        pendingSession = null
        return session
    }
}
