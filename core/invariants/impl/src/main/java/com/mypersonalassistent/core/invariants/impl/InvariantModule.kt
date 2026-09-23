package com.mypersonalassistent.core.invariants.impl

import com.mypersonalassistent.core.database.api.InvariantStorage
import com.mypersonalassistent.core.invariants.api.InvariantGuard
import com.mypersonalassistent.core.invariants.api.InvariantRepository
import com.mypersonalassistent.core.invariants.api.InvariantSemanticPort
import com.mypersonalassistent.core.invariants.api.InvariantGateStage
import com.mypersonalassistent.core.invariants.api.InvariantSnapshot
import com.mypersonalassistent.core.invariants.api.SemanticGuardResult
import org.koin.dsl.module

val invariantsModule = module {
    single<InvariantRepository> { DefaultInvariantRepository(get<InvariantStorage>()) }
    single<InvariantSemanticPort> { LlmInvariantSemanticPort(get()) }
    single<InvariantGuard> { DeterministicInvariantGuard(get()) }
}
