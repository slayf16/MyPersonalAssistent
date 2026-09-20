package com.mypersonalassistent.core.history.impl
import com.mypersonalassistent.core.database.api.ChatStorage
import com.mypersonalassistent.core.history.api.HistoryRepository
import org.koin.dsl.module
val historyModule = module { single<HistoryRepository> { RoomHistoryRepository(get<ChatStorage>()) } }
