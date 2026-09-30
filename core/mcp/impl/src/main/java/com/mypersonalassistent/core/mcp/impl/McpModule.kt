package com.mypersonalassistent.core.mcp.impl
import com.mypersonalassistent.core.mcp.api.*
import org.koin.dsl.module
val mcpModule = module {
    single<McpOperationCoordinator> { DefaultMcpOperationCoordinator() }
    single { DefaultMcpRepository(get(), get(), operations = get()) }
    single<McpCatalogRepository> { get<DefaultMcpRepository>() }
    single<ChatMcpRepository> { get<DefaultMcpRepository>() }
    single<McpToolGateway> { get<DefaultMcpRepository>() }
}
