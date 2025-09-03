package com.example.amongchat

import java.util.*

data class MeshPacket(
    val messageId: UUID,
    val originId: String,
    val seq: Long,
    val ttl: Int,
    val payloadChunk: ByteArray,
    val isLast: Boolean = false
)

object MeshConstants {
    val SERVICE_UUID: UUID = UUID.fromString("5a7e2f41-15d3-4f3e-b4c4-7b1e7da7e2a1")
    const val MAX_ADV_PAYLOAD = 22 // usable service data bytes conservatively
}