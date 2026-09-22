package com.mypersonalassistent.core.memory.api

enum class OnboardingStatus { NOT_STARTED, SKIPPED, COMPLETED }
enum class ResponseLanguage { AUTO, RUSSIAN, ENGLISH, OTHER }
enum class ResponseTone { FRIENDLY, NEUTRAL, FORMAL, OTHER }
enum class ResponseDetail { CONCISE, BALANCED, DETAILED, OTHER }

data class ProfileMemory(
    val onboardingStatus: OnboardingStatus = OnboardingStatus.NOT_STARTED,
    val preferredName: String? = null,
    val language: ResponseLanguage = ResponseLanguage.AUTO,
    val tone: ResponseTone = ResponseTone.NEUTRAL,
    val detailLevel: ResponseDetail = ResponseDetail.BALANCED,
    val customInstructions: String = "",
    val updatedAt: Long = 0L,
    val customLanguage: String = "",
    val customTone: String = "",
    val customDetailLevel: String = "",
)

data class TaskMemory(
    val chatId: String,
    val goal: String = "",
    val constraints: List<String> = emptyList(),
    val desiredResult: String = "",
    val decisions: List<String> = emptyList(),
    val updatedAt: Long = 0L,
) {
    val isEmpty: Boolean
        get() = goal.isBlank() && constraints.isEmpty() && desiredResult.isBlank() && decisions.isEmpty()
}

interface MemoryRepository {
    suspend fun readProfile(): ProfileMemory
    suspend fun saveProfile(profile: ProfileMemory): Boolean
    suspend fun skipProfile(updatedAt: Long): Boolean
    suspend fun clearProfile(updatedAt: Long): Boolean
    suspend fun readTaskMemory(chatId: String): TaskMemory
}
