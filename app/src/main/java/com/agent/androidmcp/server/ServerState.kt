package com.agent.androidmcp.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

data class ServerStatus(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val ipAddress: String = "127.0.0.1",
    val activeConnections: Int = 0,
    val totalRequests: Long = 0L,
    val lastLog: String = "Server stopped"
)

object ServerState {

    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    fun updateRunning(running: Boolean, port: Int = 8080) {
        _status.value = _status.value.copy(
            isRunning = running,
            port = port,
            ipAddress = getLocalIpAddress(),
            lastLog = if (running) "Server running on port $port" else "Server stopped"
        )
    }

    fun incrementRequests(log: String = "") {
        _status.value = _status.value.copy(
            totalRequests = _status.value.totalRequests + 1,
            lastLog = log.ifBlank { _status.value.lastLog }
        )
    }

    fun updateConnections(count: Int) {
        _status.value = _status.value.copy(activeConnections = count)
    }

    fun setLog(log: String) {
        _status.value = _status.value.copy(lastLog = log)
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress ?: ""
                        if (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}
