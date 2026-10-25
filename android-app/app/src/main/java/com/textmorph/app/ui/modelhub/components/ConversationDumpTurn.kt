package com.textmorph.app.ui.modelhub.components

import com.textmorph.app.data.model.ChatMessageEntity

/**
 * Data model representing a paired conversation turn for raw diagnostic dumping.
 */
data class ConversationDumpTurn(
    val turnNumber: Int,
    val timestamp: Long,
    val systemPrompt: String?,
    val userPrompt: String,
    val rawNakedOutput: String,
    val cleanContent: String?,
    val thoughtContent: String?,
    val actionPayloadJson: String?,
    val actionStatus: String?,
    val tokensPerSecond: Float?,
    val totalTokens: Int?,
    val durationMs: Long?,
    val isActionFailed: Boolean
) {
    companion object {
        /**
         * Pairs user requests with assistant responses from the conversation history,
         * returning turns ordered strictly from LATEST TO OLDEST.
         */
        fun extractDumpTurns(
            messages: List<ChatMessageEntity>,
            defaultSystemPrompt: String = "You are TextMorph, an on-device AI assistant."
        ): List<ConversationDumpTurn> {
            if (messages.isEmpty()) return emptyList()

            val turns = mutableListOf<ConversationDumpTurn>()
            var turnCounter = 1

            var currentUserMsg: ChatMessageEntity? = null

            for (msg in messages) {
                when (msg.role) {
                    "user" -> {
                        if (currentUserMsg != null) {
                            // User followed by another user message without assistant reply
                            turns.add(
                                ConversationDumpTurn(
                                    turnNumber = turnCounter++,
                                    timestamp = currentUserMsg.timestamp,
                                    systemPrompt = defaultSystemPrompt,
                                    userPrompt = currentUserMsg.content,
                                    rawNakedOutput = "[No Response Generated]",
                                    cleanContent = null,
                                    thoughtContent = null,
                                    actionPayloadJson = null,
                                    actionStatus = null,
                                    tokensPerSecond = null,
                                    totalTokens = null,
                                    durationMs = null,
                                    isActionFailed = false
                                )
                            )
                        }
                        currentUserMsg = msg
                    }

                    "assistant", "action" -> {
                        val userPrompt = currentUserMsg?.content ?: "[Direct System/Autonomous Trigger]"
                        val turnTime = msg.timestamp
                        val sysPrompt = msg.systemPrompt ?: defaultSystemPrompt
                        val naked = msg.rawNakedOutput?.ifBlank { null } ?: msg.content

                        val isFailed = msg.actionStatus == "failed" || msg.actionStatus == "interrupted"

                        turns.add(
                            ConversationDumpTurn(
                                turnNumber = turnCounter++,
                                timestamp = turnTime,
                                systemPrompt = sysPrompt,
                                userPrompt = userPrompt,
                                rawNakedOutput = naked,
                                cleanContent = msg.content,
                                thoughtContent = msg.thoughtContent,
                                actionPayloadJson = msg.actionPayloadJson,
                                actionStatus = msg.actionStatus,
                                tokensPerSecond = msg.tokensPerSecond,
                                totalTokens = msg.totalTokens,
                                durationMs = msg.durationMs,
                                isActionFailed = isFailed
                            )
                        )
                        currentUserMsg = null
                    }
                }
            }

            // If trailing user message without reply
            if (currentUserMsg != null) {
                turns.add(
                    ConversationDumpTurn(
                        turnNumber = turnCounter,
                        timestamp = currentUserMsg.timestamp,
                        systemPrompt = defaultSystemPrompt,
                        userPrompt = currentUserMsg.content,
                        rawNakedOutput = "[Awaiting Model Response...]",
                        cleanContent = null,
                        thoughtContent = null,
                        actionPayloadJson = null,
                        actionStatus = null,
                        tokensPerSecond = null,
                        totalTokens = null,
                        durationMs = null,
                        isActionFailed = false
                    )
                )
            }

            // Reverse chronological order: LATEST to OLDEST
            return turns.sortedByDescending { it.timestamp }
        }
    }
}
