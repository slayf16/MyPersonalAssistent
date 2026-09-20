package com.mypersonalassistent.app

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.Value
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.llm.api.Llm
import com.mypersonalassistent.feature.chat.impl.ChatFeatureComponent
import com.mypersonalassistent.core.credentials.api.CredentialRepository
import com.mypersonalassistent.feature.credentials.impl.CredentialsFeatureComponent
import com.mypersonalassistent.feature.home.impl.HomeFeatureComponent
import java.util.UUID
import kotlinx.serialization.Serializable

interface RootComponent {
    val childStack: Value<ChildStack<*, Child>>
    sealed class Child { class Credentials(val component: CredentialsComponent) : Child(); class Home(val component: HomeComponent) : Child(); class Chat(val component: ChatComponent) : Child() }
}
class CredentialsComponent(componentContext: ComponentContext, credentials: CredentialRepository, checkExisting: Boolean, val onSaved: () -> Unit) : ComponentContext by componentContext { val feature = CredentialsFeatureComponent(componentContext, credentials, checkExisting) }
class HomeComponent(componentContext: ComponentContext, history: HistoryRepository, val onNew: () -> Unit, val onOpen: (String) -> Unit, val onEditKey: () -> Unit) : ComponentContext by componentContext { val feature = HomeFeatureComponent(componentContext, history) }
class ChatComponent(componentContext: ComponentContext, id: String, history: HistoryRepository, llm: Llm, val onExit: () -> Unit) : ComponentContext by componentContext { val feature = ChatFeatureComponent(componentContext, id, history, llm) }
class DefaultRootComponent(componentContext: ComponentContext, private val credentials: CredentialRepository, private val history: HistoryRepository, private val llm: Llm) : RootComponent, ComponentContext by componentContext {
    private val navigation = StackNavigation<Config>()
    override val childStack: Value<ChildStack<*, RootComponent.Child>> = childStack(source = navigation, serializer = Config.serializer(), initialConfiguration = Config.Credentials(), handleBackButton = true, childFactory = ::child)
    private fun child(config: Config, context: ComponentContext): RootComponent.Child = when (config) {
        is Config.Credentials -> RootComponent.Child.Credentials(CredentialsComponent(context, credentials, config.checkExisting) { navigation.replaceAll(Config.Home) })
        Config.Home -> RootComponent.Child.Home(HomeComponent(context, history, onNew = { navigation.push(Config.Chat(UUID.randomUUID().toString())) }, onOpen = { navigation.push(Config.Chat(it)) }, onEditKey = { navigation.push(Config.Credentials(false)) }))
        is Config.Chat -> RootComponent.Child.Chat(ChatComponent(context, config.id, history, llm) { navigation.pop() })
    }
    @Serializable private sealed interface Config { @Serializable data class Credentials(val checkExisting: Boolean = true) : Config; @Serializable data object Home : Config; @Serializable data class Chat(val id: String) : Config }
}
