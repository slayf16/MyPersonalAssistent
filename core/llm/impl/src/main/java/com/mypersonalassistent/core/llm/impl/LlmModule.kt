package com.mypersonalassistent.core.llm.impl
import com.mypersonalassistent.core.llm.api.Llm
import org.koin.dsl.module
val llmModule = module { single { DeepSeekConfig() }; single<Llm> { DeepSeekLlm(get(), get()) } }
