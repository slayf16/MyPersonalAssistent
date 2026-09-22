package com.mypersonalassistent.core.memory.impl

import com.mypersonalassistent.core.memory.api.MemoryRepository
import org.koin.dsl.module

val memoryModule = module { single<MemoryRepository> { RoomMemoryRepository(get()) } }
