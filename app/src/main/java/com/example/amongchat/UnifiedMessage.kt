package com.example.amongchat

// Models and small enums used by UI
import java.util.UUID

/**
 * UnifiedMessage: a single message as shown to the UI
 */
data class UnifiedMessage(
    val messageId: UUID,
    val text: String,
    val senderId: String,
    val nickname: String,
    val sourceTransport: Transport,
    val receivedAt: Long
)

enum class Transport { CLASSIC, BLE, LOCAL_SENT }