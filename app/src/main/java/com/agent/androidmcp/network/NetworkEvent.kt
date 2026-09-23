package com.agent.androidmcp.network

import kotlinx.serialization.Serializable

@Serializable
data class NetworkEvent(
    val eventId: String,
    val testId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val protocol: String = "HTTPS",
    val host: String,
    val port: Int = 443,
    val method: String? = null,
    val path: String? = null,
    val statusCode: Int? = null,
    val durationMs: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val status: String = "COMPLETED",
    val error: String? = null
)
