package com.mypersonalassistent.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.defaultComponentContext
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.mypersonalassistent.core.credentials.api.CredentialRepository
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.history.api.AgentRecoveryRepository
import com.mypersonalassistent.core.memory.api.MemoryRepository
import com.mypersonalassistent.core.agent.api.AgentRunEngine
import com.mypersonalassistent.feature.chat.api.ChatEffect
import com.mypersonalassistent.feature.chat.impl.ChatScreen
import com.mypersonalassistent.feature.credentials.impl.CredentialsScreen
import com.mypersonalassistent.feature.home.impl.HomeScreen
import com.mypersonalassistent.feature.profile.api.ProfileEffect
import com.mypersonalassistent.feature.profile.impl.ProfileScreen
import com.mypersonalassistent.app.ui.theme.MyPersonalAssistentTheme
import com.mypersonalassistent.core.credentials.impl.credentialsModule
import com.mypersonalassistent.core.database.impl.databaseModule
import com.mypersonalassistent.core.history.impl.historyModule
import com.mypersonalassistent.core.llm.impl.llmModule
import com.mypersonalassistent.core.memory.impl.memoryModule
import com.mypersonalassistent.core.agent.impl.agentModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class MyPersonalAssistentApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MyPersonalAssistentApplication)
            modules(credentialsModule, databaseModule, historyModule, memoryModule, agentModule, llmModule)
        }
    }
}
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val credentials = org.koin.java.KoinJavaComponent.get<CredentialRepository>(CredentialRepository::class.java)
        val history = org.koin.java.KoinJavaComponent.get<HistoryRepository>(HistoryRepository::class.java)
        val recovery = org.koin.java.KoinJavaComponent.get<AgentRecoveryRepository>(AgentRecoveryRepository::class.java)
        val memory = org.koin.java.KoinJavaComponent.get<MemoryRepository>(MemoryRepository::class.java)
        val engine = org.koin.java.KoinJavaComponent.get<AgentRunEngine>(AgentRunEngine::class.java)
        val root = DefaultRootComponent(defaultComponentContext(), credentials, history, recovery, memory, engine)
        setContent {
            MyPersonalAssistentTheme {
                Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    RootContent(root)
                }
            }
        }
    }
}
@Composable
private fun RootContent(root: RootComponent) {
    val snackbar = remember { SnackbarHostState() }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            Children(stack = root.childStack) { child ->
                when (val page = child.instance) {
                    is RootComponent.Child.Credentials -> {
                        val feature = page.component.feature
                        LaunchedEffect(feature) {
                            for (effect in feature.effects) {
                                when (effect) {
                                    com.mypersonalassistent.feature.credentials.api.CredentialsEffect.ExistingKey,
                                    com.mypersonalassistent.feature.credentials.api.CredentialsEffect.Saved -> page.component.onSaved()
                                    com.mypersonalassistent.feature.credentials.api.CredentialsEffect.TechnicalError -> snackbar.showSnackbar("Техническая ошибка")
                                }
                            }
                        }
                        CredentialsScreen(feature.state, feature::accept)
                    }
                    is RootComponent.Child.Home -> {
                        val feature = page.component.feature
                        LaunchedEffect(feature) {
                            for (effect in feature.effects) {
                                when (effect) {
                                    com.mypersonalassistent.feature.home.api.HomeEffect.NewChat -> page.component.onNew()
                                    com.mypersonalassistent.feature.home.api.HomeEffect.EditKey -> page.component.onEditKey()
                                    com.mypersonalassistent.feature.home.api.HomeEffect.EditProfile -> page.component.onEditProfile()
                                    is com.mypersonalassistent.feature.home.api.HomeEffect.Open -> page.component.onOpen(effect.id)
                                    com.mypersonalassistent.feature.home.api.HomeEffect.TechnicalError -> snackbar.showSnackbar("Техническая ошибка")
                                }
                            }
                        }
                        HomeScreen(feature.state, feature::accept)
                    }
                    is RootComponent.Child.Profile -> {
                        val feature = page.component.feature
                        LaunchedEffect(feature) {
                            for (effect in feature.effects) {
                                when (effect) {
                                    ProfileEffect.Done -> page.component.onDone()
                                    ProfileEffect.TechnicalError -> snackbar.showSnackbar("Техническая ошибка")
                                }
                            }
                        }
                        ProfileScreen(feature.state, feature::accept)
                    }
                    is RootComponent.Child.Chat -> {
                        val feature = page.component.feature
                        LaunchedEffect(feature) {
                            for (effect in feature.effects) {
                                when (effect) {
                                    ChatEffect.TechnicalError -> snackbar.showSnackbar("Техническая ошибка")
                                    ChatEffect.NavigateHome -> page.component.onExit()
                                }
                            }
                        }
                        ChatScreen(feature.state, feature::accept)
                    }
                }
            }
        }
    }
}
