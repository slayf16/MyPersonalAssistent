package com.mypersonalassistent.core.agent.impl

import com.mypersonalassistent.core.agent.api.AgentRequestComposer
import org.koin.dsl.module

val agentModule = module { single<AgentRequestComposer> { DefaultAgentRequestComposer(get()) } }
