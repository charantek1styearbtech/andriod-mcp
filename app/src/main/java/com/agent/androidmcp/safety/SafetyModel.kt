package com.agent.androidmcp.safety

import com.agent.androidmcp.action.AgentAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable

enum class SafetyLevel {
    SAFE,
    CONFIRMATION_REQUIRED,
    BLOCKED
}

enum class RiskCategory(val title: String, val iconDesc: String) {
    SEND_MESSAGE("Send Message / Post", "Message"),
    DELETE_DATA("Delete / Erase Content", "Delete"),
    FINANCIAL_PAYMENT("Payment / Purchase", "Payment"),
    SYSTEM_SETTINGS("System / Security Settings", "Settings"),
    OTHER_SENSITIVE("Sensitive Operation", "Warning")
}

@Serializable
data class SafetyEvaluation(
    val level: SafetyLevel,
    val riskCategory: RiskCategory? = null,
    val reason: String = "",
    val preview: String = ""
)

data class ConfirmationRequest(
    val id: String = java.util.UUID.randomUUID().toString(),
    val action: AgentAction,
    val evaluation: SafetyEvaluation,
    val createdAt: Long = System.currentTimeMillis(),
    val deferredResult: CompletableDeferred<Boolean> = CompletableDeferred()
)
