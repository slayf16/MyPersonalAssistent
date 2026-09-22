package com.mypersonalassistent.core.database.impl

import com.mypersonalassistent.core.database.api.ChatStorage
import com.mypersonalassistent.core.database.api.MemoryStorage
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val databaseModule = module {
    single { RoomChatStorage.create(androidContext()) }
    single<ChatStorage> { get<RoomChatStorage>() }
    single<MemoryStorage> { get<RoomChatStorage>() }
}
