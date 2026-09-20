package com.mypersonalassistent.core.database.impl
import com.mypersonalassistent.core.database.api.ChatStorage
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
val databaseModule = module { single<ChatStorage> { RoomChatStorage.create(androidContext()) } }
