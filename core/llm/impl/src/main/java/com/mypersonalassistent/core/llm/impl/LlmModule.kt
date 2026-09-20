package com.mypersonalassistent.core.llm.impl
import com.mypersonalassistent.core.llm.api.Llm
import io.ktor.client.HttpClient
import org.koin.dsl.module

val llmModule = module {
    single { DeepSeekConfig() }
    single { DeepSeekHttpClientFactory().create() }
    single<Llm> { DeepSeekLlm(get(), get(), get<HttpClient>()) }
}
