package com.agent.androidmcp.server.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

@Serializable
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

@Serializable
data class McpTextContent(
    val type: String = "text",
    val text: String
)

@Serializable
data class McpImageContent(
    val type: String = "image",
    val data: String,
    val mimeType: String = "image/jpeg"
)
