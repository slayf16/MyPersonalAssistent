package com.mypersonalassistent.core.agent.impl

import com.mypersonalassistent.core.agent.api.AgentRequestComposer
import com.mypersonalassistent.core.agent.api.AgentRunEngine
import org.koin.dsl.module

val agentModule = module {
    single<AgentRequestComposer> { DefaultAgentRequestComposer(get()) }
    factory<AgentRunEngine> { DefaultAgentRunEngine(get(), get(), get(), get(), invariants = get(), guard = get()) }
}
