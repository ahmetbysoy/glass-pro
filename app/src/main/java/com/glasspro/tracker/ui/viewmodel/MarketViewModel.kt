package com.glasspro.tracker.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glasspro.tracker.GlassProApplication
import com.glasspro.tracker.core.model.AnalysisResult
import com.glasspro.tracker.core.model.LiquidationEvent
import com.glasspro.tracker.core.model.MarketStats
import com.glasspro.tracker.data.remote.adapter.AdapterHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state holder. Delegates all data work to the repository; keeps only the
 * navigation state and the transient banner alert local to the UI.
 */
class MarketViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as GlassProApplication).serviceLocator.repository

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    // ------------------------------------------------------------------
    // Real data flows
    // ------------------------------------------------------------------

    val liquidations: StateFlow<List<LiquidationEvent>> = repository.liquidations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val analyses: StateFlow<List<AnalysisResult>> = repository.analyses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val marketStats: StateFlow<MarketStats> = repository.marketStats

    val feedHealth: StateFlow<List<AdapterHealth>> = repository.feedHealth

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    val minThreshold: StateFlow<Double> = repository.minUsdThreshold.asStateFlow()
    val excludeBtcEth: StateFlow<Boolean> = repository.excludeBtcEth.asStateFlow()
    val isLiveStreaming: StateFlow<Boolean> = repository.isLiveStreaming.asStateFlow()
    val cooldownSec: StateFlow<Long> = repository.cooldownSec.asStateFlow()
    val tacticalHorizonMs: StateFlow<Long> = repository.tacticalHorizonMs.asStateFlow()
    val strategicHorizonMs: StateFlow<Long> = repository.strategicHorizonMs.asStateFlow()

    // ------------------------------------------------------------------
    // Transient trigger banner
    // ------------------------------------------------------------------

    private val _banner = MutableStateFlow<Pair<LiquidationEvent, String>?>(null)
    val banner: StateFlow<Pair<LiquidationEvent, String>?> = _banner.asStateFlow()

    init {
        repository.start()
        viewModelScope.launch {
            repository.bannerTrigger.collect { alert ->
                _banner.value = alert
            }
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    fun setSelectedTab(index: Int) {
        _selectedTab.value = index
    }

    fun setMinThreshold(value: Double) {
        repository.minUsdThreshold.value = value
    }

    fun setExcludeBtcEth(value: Boolean) {
        repository.excludeBtcEth.value = value
    }

    fun setLiveStreaming(value: Boolean) {
        repository.isLiveStreaming.value = value
    }

    fun setCooldownSeconds(value: Long) {
        repository.cooldownSec.value = value
    }

    fun setTacticalHorizon(value: Long) {
        repository.tacticalHorizonMs.value = value
    }

    fun setStrategicHorizon(value: Long) {
        repository.strategicHorizonMs.value = value
    }

    fun dismissBanner() {
        _banner.value = null
    }

    fun triggerManualAnalysis(symbol: String) {
        viewModelScope.launch {
            repository.triggerManualAnalysis(symbol)
            _selectedTab.value = 1
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }
}
