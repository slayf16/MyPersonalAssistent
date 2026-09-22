package com.mypersonalassistent.core.memory.impl

import com.mypersonalassistent.core.database.api.MemoryStorage
import com.mypersonalassistent.core.database.api.StorageResult
import com.mypersonalassistent.core.database.api.StoredProfile
import com.mypersonalassistent.core.database.api.StoredTaskMemory
import com.mypersonalassistent.core.memory.api.OnboardingStatus
import com.mypersonalassistent.core.memory.api.ProfileMemory
import com.mypersonalassistent.core.memory.api.ResponseDetail
import com.mypersonalassistent.core.memory.api.ResponseLanguage
import com.mypersonalassistent.core.memory.api.ResponseTone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomMemoryRepositoryTest {
    @Test fun missingMemoryReturnsExplicitDefaults() = runBlocking {
        val repository = RoomMemoryRepository(FakeStorage())
        assertEquals(OnboardingStatus.NOT_STARTED, repository.readProfile().onboardingStatus)
        assertEquals(true, repository.readTaskMemory("chat").isEmpty)
    }

    @Test fun savedAndClearedProfileAreUserControlled() = runBlocking {
        val storage = FakeStorage()
        val repository = RoomMemoryRepository(storage)
        repository.saveProfile(ProfileMemory(
            preferredName = " Алекс ",
            language = ResponseLanguage.RUSSIAN,
            tone = ResponseTone.FRIENDLY,
            detailLevel = ResponseDetail.DETAILED,
            customInstructions = " Краткий вывод сначала ",
            updatedAt = 10,
        ))
        assertEquals(OnboardingStatus.COMPLETED.name, storage.profile?.onboardingStatus)
        assertEquals("Алекс", storage.profile?.preferredName)
        assertEquals("Краткий вывод сначала", storage.profile?.customInstructions)
        repository.clearProfile(20)
        assertEquals(OnboardingStatus.SKIPPED.name, storage.profile?.onboardingStatus)
        assertNull(storage.profile?.preferredName)
    }

    @Test fun taskMemoryIsDecodedOnlyForRequestedChat() = runBlocking {
        val storage = FakeStorage(task = StoredTaskMemory(
            "a", "goal", "[\"one\",\"two\"]", "done", "[\"decision\"]", 5,
        ))
        val repository = RoomMemoryRepository(storage)
        assertEquals(listOf("one", "two"), repository.readTaskMemory("a").constraints)
        assertEquals(true, repository.readTaskMemory("b").isEmpty)
    }

    @Test fun otherValuesRoundTripTrimmedAndInactiveValuesAreCleared() = runBlocking {
        val storage = FakeStorage()
        val repository = RoomMemoryRepository(storage)
        repository.saveProfile(
            ProfileMemory(
                language = ResponseLanguage.OTHER,
                tone = ResponseTone.FORMAL,
                detailLevel = ResponseDetail.OTHER,
                customLanguage = " Klingon ",
                customTone = "must not persist",
                customDetailLevel = " " + "d".repeat(130) + " ",
            ),
        )

        assertEquals("Klingon", storage.profile?.customLanguage)
        assertEquals("", storage.profile?.customTone)
        assertEquals("d".repeat(120), storage.profile?.customDetailLevel)
        assertEquals("Klingon", repository.readProfile().customLanguage)
        assertEquals("", repository.readProfile().customTone)
    }

    private class FakeStorage(
        var profile: StoredProfile? = null,
        private val task: StoredTaskMemory? = null,
    ) : MemoryStorage {
        override suspend fun readProfile() = profile
        override suspend fun upsertProfile(profile: StoredProfile): StorageResult {
            this.profile = profile
            return StorageResult.Success
        }
        override suspend fun readTaskMemory(chatId: String) = task?.takeIf { it.chatId == chatId }
    }
}
