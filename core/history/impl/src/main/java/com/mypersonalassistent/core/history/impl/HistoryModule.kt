package com.mypersonalassistent.core.history.impl
import com.mypersonalassistent.core.database.api.ChatStorage
import com.mypersonalassistent.core.history.api.HistoryRepository
import com.mypersonalassistent.core.history.api.AgentRecoveryRepository
import org.koin.dsl.module
val historyModule = module {
    single { RoomHistoryRepository(get<ChatStorage>(), get()) }
    single<HistoryRepository> { get<RoomHistoryRepository>() }
    single<AgentRecoveryRepository> { get<RoomHistoryRepository>() }
}
