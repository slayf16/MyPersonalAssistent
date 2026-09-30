package com.mypersonalassistent.feature.settings.api
data object SettingsState
sealed interface SettingsIntent { data object OpenProfile : SettingsIntent; data object OpenInvariants : SettingsIntent; data object OpenMcp : SettingsIntent; data object Back : SettingsIntent }
sealed interface SettingsEffect { data object Profile : SettingsEffect; data object Invariants : SettingsEffect; data object Mcp : SettingsEffect; data object Back : SettingsEffect }
