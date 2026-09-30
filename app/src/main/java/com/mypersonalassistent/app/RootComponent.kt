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
import com.mypersonalassistent.core.mcp.api.McpCatalogRepository
import com.mypersonalassistent.core.mcp.api.ChatMcpRepository
import com.mypersonalassistent.core.mcp.api.McpOperationCoordinator
import com.mypersonalassistent.feature.mcpsettings.impl.McpSettingsFeatureComponent
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
        class Settings(val component: SettingsComponent) : Child()
        class McpSettings(val component: McpSettingsComponent) : Child()
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
    val onSettings: () -> Unit,
) : ComponentContext by componentContext {
    val feature = HomeFeatureComponent(componentContext, history, recovery)
}
class SettingsComponent(componentContext: ComponentContext, val onProfile: () -> Unit, val onInvariants: () -> Unit, val onMcp: () -> Unit, val onExit: () -> Unit) : ComponentContext by componentContext
class McpSettingsComponent(componentContext: ComponentContext, catalog: McpCatalogRepository, val onExit: () -> Unit) : ComponentContext by componentContext {
    val feature = McpSettingsFeatureComponent(componentContext, catalog)
}

class ChatComponent(
    componentContext: ComponentContext,
    id: String,
    history: HistoryRepository,
    memory: MemoryRepository,
    engine: AgentRunEngine,
    recovery: AgentRecoveryRepository,
    invariants: InvariantRepository,
    mcp: ChatMcpRepository,
    operations: McpOperationCoordinator,
    val onExit: () -> Unit,
    val onInvariants: () -> Unit,
) : ComponentContext by componentContext {
    val feature = ChatFeatureComponent(componentContext, id, history, memory, engine, recovery, invariants, mcp, operations)
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
    private val mcpCatalog: McpCatalogRepository,
    private val chatMcp: ChatMcpRepository,
    private val mcpOperations: McpOperationCoordinator,
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
                    onInvariants = { navigation.push(Config.Invariants) },
                    onSettings = { navigation.push(Config.Settings) })
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
                    chatMcp,
                    mcpOperations,
                    onExit = { navigation.pop() },
                    onInvariants = { navigation.push(Config.Invariants) },
                ))

            Config.Invariants -> RootComponent.Child.Invariants(InvariantsComponent(context, invariants) { navigation.pop() })
            Config.Settings -> RootComponent.Child.Settings(SettingsComponent(context, { navigation.push(Config.Profile(false)) }, { navigation.push(Config.Invariants) }, { navigation.push(Config.McpSettings) }, { navigation.pop() }))
            Config.McpSettings -> RootComponent.Child.McpSettings(McpSettingsComponent(context, mcpCatalog) { navigation.pop() })
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
        @Serializable data object Settings : Config
        @Serializable data object McpSettings : Config
    }
}
