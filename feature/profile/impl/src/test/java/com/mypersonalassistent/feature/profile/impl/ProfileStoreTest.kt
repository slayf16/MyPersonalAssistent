package com.mypersonalassistent.feature.profile.impl

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.arkivanov.mvikotlin.core.rx.Observer
import com.mypersonalassistent.core.memory.api.MemoryRepository
import com.mypersonalassistent.core.memory.api.OnboardingStatus
import com.mypersonalassistent.core.memory.api.ProfileMemory
import com.mypersonalassistent.core.memory.api.ResponseDetail
import com.mypersonalassistent.core.memory.api.ResponseLanguage
import com.mypersonalassistent.core.memory.api.ResponseTone
import com.mypersonalassistent.core.memory.api.TaskMemory
import com.mypersonalassistent.feature.profile.api.ProfileIntent
import com.mypersonalassistent.feature.profile.api.ProfileEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileStoreTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun existingProfileCanBeEditedAndSaved() = runTest(dispatcher) {
        val repository = RecordingMemory(ProfileMemory(
            onboardingStatus = OnboardingStatus.COMPLETED,
            preferredName = "Старое имя",
        ))
        val store = ProfileStoreFactory(DefaultStoreFactory(), repository, firstRun = false, clock = { 42 }).create()
        store.accept(ProfileIntent.Load)
        testScheduler.advanceUntilIdle()
        store.accept(ProfileIntent.ChangeName("Новое имя"))
        store.accept(ProfileIntent.SelectTone(ResponseTone.FRIENDLY))
        store.accept(ProfileIntent.Save)
        testScheduler.advanceUntilIdle()
        assertEquals("Новое имя", repository.saved?.preferredName)
        assertEquals(ResponseTone.FRIENDLY, repository.saved?.tone)
        assertEquals(42L, repository.saved?.updatedAt)
        store.dispose()
    }

    @Test fun firstRunCanBeSkippedWithoutInventingProfileFields() = runTest(dispatcher) {
        val repository = RecordingMemory(ProfileMemory())
        val store = ProfileStoreFactory(DefaultStoreFactory(), repository, firstRun = true, clock = { 7 }).create()
        store.accept(ProfileIntent.Load)
        testScheduler.advanceUntilIdle()
        store.accept(ProfileIntent.Skip)
        testScheduler.advanceUntilIdle()
        assertEquals(7L, repository.skippedAt)
        assertEquals(null, repository.saved)
        store.dispose()
    }

    @Test fun existingProfileCanBeClearedToTheExplicitSkippedState() = runTest(dispatcher) {
        val repository = RecordingMemory(ProfileMemory(onboardingStatus = OnboardingStatus.COMPLETED))
        val store = ProfileStoreFactory(DefaultStoreFactory(), repository, firstRun = false, clock = { 99 }).create()
        store.accept(ProfileIntent.Load)
        testScheduler.advanceUntilIdle()
        store.accept(ProfileIntent.Clear)
        testScheduler.advanceUntilIdle()

        assertEquals(99L, repository.clearedAt)
        store.dispose()
    }

    @Test fun failedClearUnlocksTheStoreAndReportsTechnicalError() = runTest(dispatcher) {
        val repository = RecordingMemory(ProfileMemory(onboardingStatus = OnboardingStatus.COMPLETED), failClear = true)
        val store = ProfileStoreFactory(DefaultStoreFactory(), repository, firstRun = false).create()
        val effects = mutableListOf<ProfileEffect>()
        val collection = store.labels(object : Observer<ProfileEffect> {
            override fun onNext(value: ProfileEffect) { effects += value }
            override fun onComplete() = Unit
        })
        store.accept(ProfileIntent.Load)
        testScheduler.advanceUntilIdle()
        store.accept(ProfileIntent.Clear)
        testScheduler.advanceUntilIdle()

        assertFalse(store.state.saving)
        assertEquals(listOf(ProfileEffect.TechnicalError), effects)
        collection.dispose()
        store.dispose()
    }

    @Test fun cancelledLoadIsNotConvertedToTechnicalError() = runTest(dispatcher) {
        val repository = RecordingMemory(ProfileMemory(), cancelRead = true)
        val store = ProfileStoreFactory(DefaultStoreFactory(), repository, firstRun = false).create()
        val effects = mutableListOf<ProfileEffect>()
        val collection = store.labels(object : Observer<ProfileEffect> {
            override fun onNext(value: ProfileEffect) { effects += value }
            override fun onComplete() = Unit
        })
        store.accept(ProfileIntent.Load)
        testScheduler.runCurrent()
        store.dispose()
        testScheduler.advanceUntilIdle()

        assertEquals(true, repository.readCancelled)
        assertEquals(emptyList<ProfileEffect>(), effects)
        collection.dispose()
    }

    @Test fun otherValuesRequireNonBlankInputAndAreTrimmedBeforeSaving() = runTest(dispatcher) {
        val repository = RecordingMemory(ProfileMemory())
        val store = ProfileStoreFactory(DefaultStoreFactory(), repository, firstRun = true, clock = { 123 }).create()
        store.accept(ProfileIntent.Load)
        testScheduler.advanceUntilIdle()
        store.accept(ProfileIntent.SelectLanguage(ResponseLanguage.OTHER))
        store.accept(ProfileIntent.SelectTone(ResponseTone.OTHER))
        store.accept(ProfileIntent.SelectDetail(ResponseDetail.OTHER))
        store.accept(ProfileIntent.Save)

        assertTrue(store.state.languageError)
        assertTrue(store.state.toneError)
        assertTrue(store.state.detailError)
        assertEquals(null, repository.saved)

        store.accept(ProfileIntent.ChangeCustomLanguage(" Klingon "))
        store.accept(ProfileIntent.ChangeCustomTone(" warm "))
        store.accept(ProfileIntent.ChangeCustomDetail(" short "))
        store.accept(ProfileIntent.Save)
        testScheduler.advanceUntilIdle()

        assertEquals(ResponseLanguage.OTHER, repository.saved?.language)
        assertEquals(ResponseTone.OTHER, repository.saved?.tone)
        assertEquals(ResponseDetail.OTHER, repository.saved?.detailLevel)
        assertEquals("Klingon", repository.saved?.customLanguage)
        assertEquals("warm", repository.saved?.customTone)
        assertEquals("short", repository.saved?.customDetailLevel)
        store.dispose()
    }

    @Test fun selectionAwayFromOtherClearsOnlyTheDependentDraft() = runTest(dispatcher) {
        val repository = RecordingMemory(
            ProfileMemory(
                onboardingStatus = OnboardingStatus.COMPLETED,
                language = ResponseLanguage.OTHER,
                tone = ResponseTone.OTHER,
                detailLevel = ResponseDetail.OTHER,
                customLanguage = "Klingon",
                customTone = "warm",
                customDetailLevel = "short",
            ),
        )
        val store = ProfileStoreFactory(DefaultStoreFactory(), repository, firstRun = false).create()
        store.accept(ProfileIntent.Load)
        testScheduler.advanceUntilIdle()
        store.accept(ProfileIntent.SelectTone(ResponseTone.FORMAL))

        assertEquals("Klingon", store.state.customLanguage)
        assertEquals("", store.state.customTone)
        assertEquals("short", store.state.customDetailLevel)
        store.dispose()
    }

    private class RecordingMemory(
        private val profile: ProfileMemory,
        private val failClear: Boolean = false,
        private val cancelRead: Boolean = false,
    ) : MemoryRepository {
        var saved: ProfileMemory? = null
        var skippedAt: Long? = null
        var clearedAt: Long? = null
        var readCancelled = false
        override suspend fun readProfile(): ProfileMemory = if (cancelRead) {
            try {
                awaitCancellation()
            } finally {
                readCancelled = true
            }
        } else profile
        override suspend fun saveProfile(profile: ProfileMemory): Boolean { saved = profile; return true }
        override suspend fun skipProfile(updatedAt: Long): Boolean { skippedAt = updatedAt; return true }
        override suspend fun clearProfile(updatedAt: Long): Boolean {
            if (failClear) return false
            clearedAt = updatedAt
            return true
        }
        override suspend fun readTaskMemory(chatId: String) = TaskMemory(chatId)
    }
}
