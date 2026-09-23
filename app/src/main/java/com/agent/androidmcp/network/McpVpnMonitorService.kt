package com.agent.androidmcp.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.agent.androidmcp.MainActivity
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class McpVpnMonitorService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)

    companion object {
        private const val TAG = "McpVpnMonitorService"
        const val ACTION_START = "com.agent.androidmcp.network.START"
        const val ACTION_STOP = "com.agent.androidmcp.network.STOP"
        const val EXTRA_TEST_ID = "extra_test_id"
        private const val CHANNEL_ID = "mcp_vpn_monitor_channel"
        private const val NOTIFICATION_ID = 2001

        private val _isServiceActive = AtomicBoolean(false)
        val isServiceActive: Boolean get() = _isServiceActive.get()

        fun isVpnPrepared(context: Context): Boolean {
            return prepare(context) == null
        }

        fun getPrepareIntent(context: Context): Intent? {
            return prepare(context)
        }

        fun start(context: Context, testId: String? = null) {
            val intent = Intent(context, McpVpnMonitorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TEST_ID, testId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, McpVpnMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        val testId = intent?.getStringExtra(EXTRA_TEST_ID)

        when (action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification(testId))
                startVpn(testId)
            }
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startVpn(testId: String?) {
        if (isRunning.get()) {
            Log.w(TAG, "VPN Monitor is already active.")
            return
        }

        try {
            val builder = Builder()
                .setSession("AI Test Network Monitor")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)

            // Allow bypass so the MCP WebSocket to the backend remains direct
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            vpnInterface = builder.establish()

            if (vpnInterface != null) {
                isRunning.set(true)
                _isServiceActive.set(true)
                NetworkFlowTracker.startCapture(testId)
                Log.i(TAG, "VPN interface established successfully. Monitoring started for test: $testId")
                startPacketProcessor()
            } else {
                Log.e(TAG, "Failed to establish VPN interface (null). Has permission been granted?")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting VPN service", e)
            stopSelf()
        }
    }

    private fun startPacketProcessor() {
        scope.launch {
            val pfd = vpnInterface ?: return@launch
            val inputStream = FileInputStream(pfd.fileDescriptor)
            val buffer = ByteBuffer.allocate(32767)

            try {
                while (isRunning.get() && isActive) {
                    val length = withContext(Dispatchers.IO) {
                        inputStream.read(buffer.array())
                    }
                    if (length > 0) {
                        parseAndRecordPacket(buffer.array(), length)
                        buffer.clear()
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "VPN read loop error: ${e.message}")
                }
            }
        }
    }

    /**
     * Parses IPv4/IPv6 packet headers to extract destination IP, protocol, and flow metrics.
     */
    private fun parseAndRecordPacket(packet: ByteArray, length: Int) {
        if (length < 20) return

        try {
            val version = (packet[0].toInt() shr 4) and 0x0F
            if (version != 4) return // Focus on IPv4 for simplicity

            val protocol = packet[9].toInt() and 0xFF // 6 = TCP, 17 = UDP
            val protocolName = when (protocol) {
                6 -> "TCP"
                17 -> "UDP"
                else -> "IP-$protocol"
            }

            // Extract destination IP
            val destIpBytes = ByteArray(4)
            System.arraycopy(packet, 16, destIpBytes, 0, 4)
            val destIp = InetAddress.getByAddress(destIpBytes).hostAddress ?: "unknown"

            // Ignore private / loopback addresses
            if (destIp.startsWith("10.") || destIp.startsWith("127.")) return

            // Extract destination port from TCP/UDP header
            val ihl = (packet[0].toInt() and 0x0F) * 4
            var destPort = 0
            if (length >= ihl + 4) {
                destPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            }

            val isHttp = destPort == 80
            val isHttps = destPort == 443

            val event = NetworkEvent(
                eventId = "pkt_${UUID.randomUUID().toString().substring(0, 8)}",
                testId = NetworkFlowTracker.getActiveTestId(),
                timestamp = System.currentTimeMillis(),
                protocol = if (isHttps) "HTTPS" else if (isHttp) "HTTP" else protocolName,
                host = destIp,
                port = destPort,
                bytesSent = length.toLong(),
                bytesReceived = 0,
                durationMs = 0,
                status = "COMPLETED"
            )

            NetworkFlowTracker.recordEvent(event)
        } catch (e: Exception) {
            // Ignore packet parse exceptions to avoid interrupting stream
        }
    }

    private fun stopVpn() {
        isRunning.set(false)
        _isServiceActive.set(false)
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        NetworkFlowTracker.stopCapture()
        Log.i(TAG, "VPN Monitor stopped.")
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MCP Network Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors app network traffic for AI automated testing."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(testId: String?): Notification {
        val title = "AI Network Monitor Active"
        val content = if (testId != null) "Observing network for test: $testId" else "Observing app network flows"

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }
}
