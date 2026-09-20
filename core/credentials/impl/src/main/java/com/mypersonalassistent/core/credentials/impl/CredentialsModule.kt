package com.mypersonalassistent.core.credentials.impl
import com.mypersonalassistent.core.credentials.api.CredentialRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
val credentialsModule = module { single<CredentialRepository> { SecureCredentialRepository(androidContext()) } }
