package com.mypersonalassistent.core.memory.impl

import com.mypersonalassistent.core.database.api.MemoryStorage
import com.mypersonalassistent.core.database.api.StorageResult
import com.mypersonalassistent.core.database.api.StoredProfile
import com.mypersonalassistent.core.memory.api.MemoryRepository
import com.mypersonalassistent.core.memory.api.OnboardingStatus
import com.mypersonalassistent.core.memory.api.ProfileMemory
import com.mypersonalassistent.core.memory.api.ResponseDetail
import com.mypersonalassistent.core.memory.api.ResponseLanguage
import com.mypersonalassistent.core.memory.api.ResponseTone
import com.mypersonalassistent.core.memory.api.TaskMemory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class RoomMemoryRepository(
    private val storage: MemoryStorage,
    private val json: Json = Json,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MemoryRepository {
    override suspend fun readProfile(): ProfileMemory = withContext(dispatcher) {
        storage.readProfile()?.let {
            ProfileMemory(
                onboardingStatus = OnboardingStatus.valueOf(it.onboardingStatus),
                preferredName = it.preferredName,
                language = ResponseLanguage.valueOf(it.language),
                tone = ResponseTone.valueOf(it.tone),
                detailLevel = ResponseDetail.valueOf(it.detailLevel),
                customInstructions = it.customInstructions,
                updatedAt = it.updatedAt,
                customLanguage = it.customLanguage,
                customTone = it.customTone,
                customDetailLevel = it.customDetailLevel,
            )
        } ?: ProfileMemory()
    }

    override suspend fun saveProfile(profile: ProfileMemory): Boolean = withContext(dispatcher) {
        storage.upsertProfile(profile.copy(onboardingStatus = OnboardingStatus.COMPLETED).toStored()) is StorageResult.Success
    }

    override suspend fun skipProfile(updatedAt: Long): Boolean = withContext(dispatcher) {
        storage.upsertProfile(ProfileMemory(onboardingStatus = OnboardingStatus.SKIPPED, updatedAt = updatedAt).toStored()) is StorageResult.Success
    }

    override suspend fun clearProfile(updatedAt: Long): Boolean = skipProfile(updatedAt)

    override suspend fun readTaskMemory(chatId: String): TaskMemory = withContext(dispatcher) {
        storage.readTaskMemory(chatId)?.let {
            TaskMemory(
                chatId = it.chatId,
                goal = it.goal,
                constraints = json.decodeFromString(it.constraintsJson),
                desiredResult = it.desiredResult,
                decisions = json.decodeFromString(it.decisionsJson),
                updatedAt = it.updatedAt,
            )
        } ?: TaskMemory(chatId)
    }

    private fun ProfileMemory.toStored() = StoredProfile(
        onboardingStatus = onboardingStatus.name,
        preferredName = preferredName?.trim()?.takeIf { it.isNotEmpty() },
        language = language.name,
        tone = tone.name,
        detailLevel = detailLevel.name,
        customInstructions = customInstructions.trim(),
        updatedAt = updatedAt,
        customLanguage = customLanguage.normalizedWhen(language == ResponseLanguage.OTHER),
        customTone = customTone.normalizedWhen(tone == ResponseTone.OTHER),
        customDetailLevel = customDetailLevel.normalizedWhen(detailLevel == ResponseDetail.OTHER),
    )

    private fun String.normalizedWhen(selected: Boolean): String =
        if (selected) trim().take(CUSTOM_VALUE_LIMIT) else ""

    private companion object {
        const val CUSTOM_VALUE_LIMIT = 120
    }
}
