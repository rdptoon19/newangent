package com.otgcam.agent.model

/**
 * Immutable application configuration loaded from EncryptedSharedPreferences.
 *
 * @property botToken       Telegram bot token (from BotFather)
 * @property chatId         Target Telegram chat ID for uploads and signaling
 * @property agentId        Short alphanumeric identifier for this Agent device
 * @property stunServerUrl  STUN server URI used for WebRTC ICE negotiation
 */
data class AppConfig(
    val botToken: String,
    val chatId: String,
    val agentId: String,
    val stunServerUrl: String = "stun:stun.l.google.com:19302"
)
