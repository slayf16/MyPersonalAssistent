package com.mypersonalassistent.app

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.Value
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.history.api.AgentRecoveryRepository
import com.mypersonalassistent.core.memory.api.MemoryRepository
import com.mypersonalassistent.core.agent.api.AgentRunEngine
import com.mypersonalassistent.feature.chat.impl.ChatFeatureComponent
import com.mypersonalassistent.core.credentials.api.CredentialRepository
import com.mypersonalassistent.feature.credentials.impl.CredentialsFeatureComponent
import com.mypersonalassistent.feature.home.impl.HomeFeatureComponent
import com.mypersonalassistent.feature.profile.impl.ProfileFeatureComponent
import com.mypersonalassistent.feature.invariants.impl.InvariantsFeatureComponent
import com.mypersonalassistent.core.invariants.api.InvariantRepository
import java.util.UUID
import kotlinx.serialization.Serializable

interface RootComponent {
    val childStack: Value<ChildStack<*, Child>>

    sealed class Child {
        class Credentials(val component: CredentialsComponent) : Child();
        class Profile(val component: ProfileComponent) : Child();
        class Home(val component: HomeComponent) : Child();
        class Chat(val component: ChatComponent) : Child()
        class Invariants(val component: InvariantsComponent) : Child()
    }
}

class ProfileComponent(
    componentContext: ComponentContext,
    memory: MemoryRepository,
    firstRun: Boolean,
    val onDone: () -> Unit,
) : ComponentContext by componentContext {
    val feature = ProfileFeatureComponent(componentContext, memory, firstRun)
}

class CredentialsComponent(
    componentContext: ComponentContext,
    credentials: CredentialRepository,
    checkExisting: Boolean,
    val onSaved: () -> Unit
) : ComponentContext by componentContext {
    val feature = CredentialsFeatureComponent(componentContext, credentials, checkExisting)
}

class HomeComponent(
    componentContext: ComponentContext,
    history: HistoryRepository,
    recovery: AgentRecoveryRepository,
    val onNew: () -> Unit,
    val onOpen: (String) -> Unit,
    val onEditKey: () -> Unit,
    val onEditProfile: () -> Unit,
    val onInvariants: () -> Unit,
) : ComponentContext by componentContext {
    val feature = HomeFeatureComponent(componentContext, history, recovery)
}

class ChatComponent(
    componentContext: ComponentContext,
    id: String,
    history: HistoryRepository,
    memory: MemoryRepository,
    engine: AgentRunEngine,
    recovery: AgentRecoveryRepository,
    invariants: InvariantRepository,
    val onExit: () -> Unit,
    val onInvariants: () -> Unit,
) : ComponentContext by componentContext {
    val feature = ChatFeatureComponent(componentContext, id, history, memory, engine, recovery, invariants)
}

class InvariantsComponent(
    componentContext: ComponentContext,
    repository: InvariantRepository,
    val onExit: () -> Unit,
) : ComponentContext by componentContext {
    val feature = InvariantsFeatureComponent(componentContext, repository)
}

class DefaultRootComponent(
    componentContext: ComponentContext,
    private val credentials: CredentialRepository,
    private val history: HistoryRepository,
    private val recovery: AgentRecoveryRepository,
    private val memory: MemoryRepository,
    private val engine: AgentRunEngine,
    private val invariants: InvariantRepository,
) : RootComponent, ComponentContext by componentContext {
    private val navigation = StackNavigation<Config>()
    override val childStack: Value<ChildStack<*, RootComponent.Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = Config.Credentials(),
        handleBackButton = true,
        childFactory = ::child
    )

    @OptIn(DelicateDecomposeApi::class)
    private fun child(config: Config, context: ComponentContext): RootComponent.Child =
        when (config) {
            is Config.Credentials -> RootComponent.Child.Credentials(
                CredentialsComponent(
                    context,
                    credentials,
                    config.checkExisting
                ) { navigation.replaceAll(Config.Profile(firstRun = true)) })

            is Config.Profile -> RootComponent.Child.Profile(
                ProfileComponent(context, memory, config.firstRun) {
                    if (config.firstRun) navigation.replaceAll(Config.Home) else navigation.pop()
                })

            Config.Home -> RootComponent.Child.Home(
                HomeComponent(
                    context,
                    history,
                    recovery,
                    onNew = { navigation.push(Config.Chat(UUID.randomUUID().toString())) },
                    onOpen = { navigation.push(Config.Chat(it)) },
                    onEditKey = { navigation.push(Config.Credentials(false)) },
                    onEditProfile = { navigation.push(Config.Profile(firstRun = false)) },
                    onInvariants = { navigation.push(Config.Invariants) })
            )

            is Config.Chat -> RootComponent.Child.Chat(
                ChatComponent(
                    context,
                    config.id,
                    history,
                    memory,
                    engine,
                    recovery,
                    invariants,
                    onExit = { navigation.pop() },
                    onInvariants = { navigation.push(Config.Invariants) },
                ))

            Config.Invariants -> RootComponent.Child.Invariants(InvariantsComponent(context, invariants) { navigation.pop() })
        }

    @Serializable
    private sealed interface Config {
        @Serializable
        data class Credentials(val checkExisting: Boolean = true) : Config;
        @Serializable
        data class Profile(val firstRun: Boolean) : Config;
        @Serializable
        data object Home : Config;
        @Serializable
        data class Chat(val id: String) : Config
        @Serializable
        data object Invariants : Config
    }
}
