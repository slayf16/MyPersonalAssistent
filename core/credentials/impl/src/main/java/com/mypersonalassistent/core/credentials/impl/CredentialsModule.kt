package com.mypersonalassistent.core.credentials.impl
import com.mypersonalassistent.core.credentials.api.CredentialRepository
import com.mypersonalassistent.core.credentials.api.McpSecretRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
val credentialsModule = module {
    single { SecureCredentialRepository(androidContext()) }
    single<CredentialRepository> { get<SecureCredentialRepository>() }
    single<McpSecretRepository> { get<SecureCredentialRepository>() }
}
