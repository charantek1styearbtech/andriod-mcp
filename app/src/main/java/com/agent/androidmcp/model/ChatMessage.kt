package com.agent.androidmcp.model

import java.util.UUID

enum class MessageSender {
    USER,
    AGENT,
    SYSTEM
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val actionSummary: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
