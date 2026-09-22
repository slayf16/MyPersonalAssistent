package com.mypersonalassistent.feature.profile.impl

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.mypersonalassistent.core.memory.api.MemoryRepository
import com.mypersonalassistent.core.memory.api.OnboardingStatus
import com.mypersonalassistent.core.memory.api.ProfileMemory
import com.mypersonalassistent.core.memory.api.ResponseDetail
import com.mypersonalassistent.core.memory.api.ResponseLanguage
import com.mypersonalassistent.core.memory.api.ResponseTone
import com.mypersonalassistent.feature.profile.api.ProfileEffect
import com.mypersonalassistent.feature.profile.api.ProfileIntent
import com.mypersonalassistent.feature.profile.api.ProfileState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal interface ProfileStore : Store<ProfileIntent, ProfileState, ProfileEffect>

internal class ProfileStoreFactory(
    private val factory: StoreFactory,
    private val repository: MemoryRepository,
    private val firstRun: Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun create(): ProfileStore = object : ProfileStore,
        Store<ProfileIntent, ProfileState, ProfileEffect> by factory.create(
            name = "ProfileStore",
            initialState = ProfileState(firstRun),
            bootstrapper = null,
            executorFactory = ::Executor,
            reducer = ReducerImpl,
        ) {}

    private sealed interface Message { data class State(val value: ProfileState) : Message }

    private inner class Executor : CoroutineExecutor<ProfileIntent, Nothing, ProfileState, Message, ProfileEffect>() {
        override fun executeIntent(intent: ProfileIntent) {
            when (intent) {
                ProfileIntent.Load -> load()
                is ProfileIntent.ChangeName -> update { copy(preferredName = intent.value.take(NAME_LIMIT)) }
                is ProfileIntent.ChangeInstructions -> update { copy(customInstructions = intent.value.take(INSTRUCTIONS_LIMIT)) }
                is ProfileIntent.SelectLanguage -> update {
                    copy(
                        language = intent.value,
                        customLanguage = customLanguage.takeIf { intent.value == ResponseLanguage.OTHER }.orEmpty(),
                        languageError = false,
                    )
                }
                is ProfileIntent.SelectTone -> update {
                    copy(
                        tone = intent.value,
                        customTone = customTone.takeIf { intent.value == ResponseTone.OTHER }.orEmpty(),
                        toneError = false,
                    )
                }
                is ProfileIntent.SelectDetail -> update {
                    copy(
                        detailLevel = intent.value,
                        customDetailLevel = customDetailLevel.takeIf { intent.value == ResponseDetail.OTHER }.orEmpty(),
                        detailError = false,
                    )
                }
                is ProfileIntent.ChangeCustomLanguage -> update {
                    copy(customLanguage = intent.value.take(CUSTOM_VALUE_LIMIT), languageError = false)
                }
                is ProfileIntent.ChangeCustomTone -> update {
                    copy(customTone = intent.value.take(CUSTOM_VALUE_LIMIT), toneError = false)
                }
                is ProfileIntent.ChangeCustomDetail -> update {
                    copy(customDetailLevel = intent.value.take(CUSTOM_VALUE_LIMIT), detailError = false)
                }
                ProfileIntent.Save -> save()
                ProfileIntent.Skip -> skip()
                ProfileIntent.Clear -> clear()
                ProfileIntent.Back -> if (!state().saving) publish(ProfileEffect.Done)
            }
        }

        private fun load() {
            scope.launch {
                try {
                    val profile = repository.readProfile()
                    if (firstRun && profile.onboardingStatus != OnboardingStatus.NOT_STARTED) {
                        publish(ProfileEffect.Done)
                    } else {
                        dispatch(Message.State(ProfileState(
                            firstRun = firstRun,
                            loading = false,
                            preferredName = profile.preferredName.orEmpty(),
                            language = profile.language,
                            tone = profile.tone,
                            detailLevel = profile.detailLevel,
                            customLanguage = profile.customLanguage,
                            customTone = profile.customTone,
                            customDetailLevel = profile.customDetailLevel,
                            customInstructions = profile.customInstructions,
                        )))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    dispatch(Message.State(state().copy(loading = false)))
                    publish(ProfileEffect.TechnicalError)
                }
            }
        }

        private fun save() {
            val current = state()
            val invalidLanguage = current.language == ResponseLanguage.OTHER && current.customLanguage.isBlank()
            val invalidTone = current.tone == ResponseTone.OTHER && current.customTone.isBlank()
            val invalidDetail = current.detailLevel == ResponseDetail.OTHER && current.customDetailLevel.isBlank()
            if (invalidLanguage || invalidTone || invalidDetail) {
                dispatch(Message.State(current.copy(
                    languageError = invalidLanguage,
                    toneError = invalidTone,
                    detailError = invalidDetail,
                )))
                return
            }
            persist {
            repository.saveProfile(
                ProfileMemory(
                    onboardingStatus = OnboardingStatus.COMPLETED,
                    preferredName = state().preferredName.trim().takeIf(String::isNotEmpty),
                    language = state().language,
                    tone = state().tone,
                    detailLevel = state().detailLevel,
                    customInstructions = state().customInstructions.trim(),
                    updatedAt = clock(),
                    customLanguage = state().customLanguage.trim(),
                    customTone = state().customTone.trim(),
                    customDetailLevel = state().customDetailLevel.trim(),
                )
            )
            }
        }

        private fun skip() {
            if (!state().firstRun) return
            persist { repository.skipProfile(clock()) }
        }

        private fun clear() {
            if (state().firstRun) return
            persist { repository.clearProfile(clock()) }
        }

        private fun persist(block: suspend () -> Boolean) {
            if (state().loading || state().saving) return
            dispatch(Message.State(state().copy(saving = true)))
            scope.launch {
                try {
                    if (block()) publish(ProfileEffect.Done)
                    else fail()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    fail()
                }
            }
        }

        private fun fail() {
            dispatch(Message.State(state().copy(saving = false)))
            publish(ProfileEffect.TechnicalError)
        }

        private fun update(block: ProfileState.() -> ProfileState) {
            if (!state().loading && !state().saving) dispatch(Message.State(state().block()))
        }
    }

    private object ReducerImpl : Reducer<ProfileState, Message> {
        override fun ProfileState.reduce(msg: Message): ProfileState = when (msg) {
            is Message.State -> msg.value
        }
    }

    private companion object {
        const val NAME_LIMIT = 80
        const val CUSTOM_VALUE_LIMIT = 120
        const val INSTRUCTIONS_LIMIT = 1_000
    }
}
