package com.glasspro.tracker.core.di

import android.content.Context
import com.glasspro.tracker.core.config.Watchlist
import com.glasspro.tracker.data.db.GlassDatabase
import com.glasspro.tracker.data.engine.DeribitOptionsProvider
import com.glasspro.tracker.data.engine.MarketAnalysisEngine
import com.glasspro.tracker.data.remote.LiquidationFeedManager
import com.glasspro.tracker.data.remote.MacroService
import com.glasspro.tracker.data.remote.MarketDataService
import com.glasspro.tracker.data.remote.adapter.BinanceFuturesAdapter
import com.glasspro.tracker.data.remote.adapter.BitgetAdapter
import com.glasspro.tracker.data.remote.adapter.BybitAdapter
import com.glasspro.tracker.data.remote.adapter.ExchangeAdapter
import com.glasspro.tracker.data.remote.adapter.GateFuturesAdapter
import com.glasspro.tracker.data.remote.adapter.HyperliquidAdapter
import com.glasspro.tracker.data.remote.adapter.OkxAdapter
import com.glasspro.tracker.data.remote.rest.RestClient
import com.glasspro.tracker.data.remote.ws.WebSocketClient
import com.glasspro.tracker.data.repository.MarketRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container. Everything is constructed here exactly once
 * and shared; the dependency graph is visible in one place.
 */
class ServiceLocator(context: Context) {

    private val appContext = context.applicationContext

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val restClient = RestClient(RestClient.buildOkHttpClient())
    private val wsOkHttpClient = WebSocketClient.buildOkHttpClient()

    private fun wsClient(name: String) = WebSocketClient(name, wsOkHttpClient, appScope)

    private val binanceAdapter: ExchangeAdapter by lazy {
        BinanceFuturesAdapter(restClient) { wsClient("binance-futures") }
    }
    private val okxAdapter: ExchangeAdapter by lazy {
        OkxAdapter(restClient, { wsClient("okx-public") }, { Watchlist.defaultSymbols })
    }
    private val bybitAdapter: ExchangeAdapter by lazy {
        BybitAdapter(restClient) { Watchlist.defaultSymbols }
    }
    private val bitgetAdapter: ExchangeAdapter by lazy {
        BitgetAdapter(restClient)
    }
    private val hyperliquidAdapter: ExchangeAdapter by lazy {
        HyperliquidAdapter(restClient)
    }
    private val gateAdapter: ExchangeAdapter by lazy {
        GateFuturesAdapter(restClient)
    }

    private val adapters: List<ExchangeAdapter> by lazy {
        listOf(
            binanceAdapter,
            okxAdapter,
            bybitAdapter,
            bitgetAdapter,
            hyperliquidAdapter,
            gateAdapter
        )
    }

    private val db: GlassDatabase by lazy { GlassDatabase.getInstance(appContext) }

    private val feedManager: LiquidationFeedManager by lazy {
        LiquidationFeedManager(adapters, appScope)
    }

    private val marketDataService: MarketDataService by lazy {
        MarketDataService(adapters)
    }

    private val macroService: MacroService by lazy {
        MacroService(restClient)
    }

    private val optionsProvider: DeribitOptionsProvider by lazy {
        DeribitOptionsProvider(restClient)
    }

    private val analysisEngine: MarketAnalysisEngine by lazy {
        MarketAnalysisEngine(marketDataService, feedManager, macroService, optionsProvider)
    }

    val repository: MarketRepository by lazy {
        MarketRepository(
            db = db,
            adapters = adapters,
            feedManager = feedManager,
            marketDataService = marketDataService,
            analysisEngine = analysisEngine,
            scope = appScope
        )
    }
}
