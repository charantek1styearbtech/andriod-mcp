package com.agent.androidmcp.network

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

object NetworkFlowTracker {

    private const val MAX_EVENTS = 500
    private val eventQueue = ConcurrentLinkedDeque<NetworkEvent>()
    private val isMonitoring = AtomicBoolean(false)
    private var activeTestId: String? = null
    private var captureStartTime: Long = 0

    fun startCapture(testId: String? = null) {
        activeTestId = testId
        captureStartTime = System.currentTimeMillis()
        isMonitoring.set(true)
    }

    fun stopCapture(): Map<String, Any> {
        val summary = getSummary(activeTestId)
        isMonitoring.set(false)
        activeTestId = null
        return summary
    }

    fun isCapturing(): Boolean = isMonitoring.get()

    fun getActiveTestId(): String? = activeTestId

    fun recordEvent(event: NetworkEvent) {
        // Enforce test scope if a specific test is active
        val finalEvent = if (activeTestId != null && event.testId == null) {
            event.copy(testId = activeTestId)
        } else {
            event
        }

        eventQueue.addLast(finalEvent)
        while (eventQueue.size > MAX_EVENTS) {
            eventQueue.pollFirst()
        }
    }

    fun getEvents(testId: String? = null, host: String? = null, limit: Int = 50): List<NetworkEvent> {
        return eventQueue.asSequence()
            .filter { event ->
                (testId == null || event.testId == testId) &&
                (host == null || event.host.contains(host, ignoreCase = true))
            }
            .take(limit)
            .toList()
    }

    fun getActiveConnections(): List<NetworkEvent> {
        return eventQueue.filter { it.status == "ACTIVE" }
    }

    fun clear() {
        eventQueue.clear()
    }

    fun getSummary(testId: String? = null): Map<String, Any> {
        val events = if (testId != null) {
            eventQueue.filter { it.testId == testId }
        } else {
            eventQueue.toList()
        }

        val totalRequests = events.size
        val failedRequests = events.count { it.status == "FAILED" || (it.statusCode != null && it.statusCode >= 400) }
        val totalBytesSent = events.sumOf { it.bytesSent }
        val totalBytesReceived = events.sumOf { it.bytesReceived }
        val avgDurationMs = if (totalRequests > 0) events.map { it.durationMs }.average().toLong() else 0L

        return mapOf(
            "testId" to (testId ?: "default"),
            "totalRequests" to totalRequests,
            "failedRequests" to failedRequests,
            "http5xxErrors" to events.count { it.statusCode != null && it.statusCode >= 500 },
            "http4xxErrors" to events.count { it.statusCode != null && it.statusCode in 400..499 },
            "totalBytesSent" to totalBytesSent,
            "totalBytesReceived" to totalBytesReceived,
            "avgDurationMs" to avgDurationMs,
            "isMonitoring" to isMonitoring.get()
        )
    }
}
