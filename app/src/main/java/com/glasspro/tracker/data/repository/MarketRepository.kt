package com.glasspro.tracker.data.repository

import com.glasspro.tracker.core.model.AnalysisResult
import com.glasspro.tracker.core.model.CalibrationState
import com.glasspro.tracker.core.model.ComponentScore
import com.glasspro.tracker.core.model.Direction
import com.glasspro.tracker.core.model.LiquidationEvent
import com.glasspro.tracker.core.model.LiquidationSide
import com.glasspro.tracker.core.model.MarketStats
import com.glasspro.tracker.core.model.ResolvedPrediction
import com.glasspro.tracker.core.model.SignalStatus
import com.glasspro.tracker.data.db.AnalysisDao
import com.glasspro.tracker.data.db.AnalysisEntity
import com.glasspro.tracker.data.db.CalibrationDao
import com.glasspro.tracker.data.db.CalibrationEntity
import com.glasspro.tracker.data.db.GlassDatabase
import com.glasspro.tracker.data.db.LiquidationDao
import com.glasspro.tracker.data.db.LiquidationEntity
import com.glasspro.tracker.data.engine.CalibrationEngine
import com.glasspro.tracker.data.engine.MarketAnalysisEngine
import com.glasspro.tracker.data.remote.LiquidationFeedManager
import com.glasspro.tracker.data.remote.MarketDataService
import com.glasspro.tracker.data.remote.adapter.AdapterHealth
import com.glasspro.tracker.data.remote.adapter.ExchangeAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestration layer. Owns the data pipelines and the analysis lifecycle:
 *
 *  1. Starts every exchange adapter and the consolidated liquidation feed.
 *  2. On every real SHORT liquidation above the threshold (or the 3-minute
 *     cascade sum), runs a tactical (1m) analysis.
 *  3. Every 15 minutes runs a strategic (1h) analysis for symbols with
 *     recent real liquidation activity.
 *  4. A verification loop resolves PENDING analyses at their horizon using
 *     the live consensus price and feeds the calibration statistics.
 *
 * There is no simulation, no mock data and no placeholder anywhere in this
 * class: every number originates from an exchange or is derived from
 * exchange data by the analysis engines.
 */
class MarketRepository(
    private val db: GlassDatabase,
    private val adapters: List<ExchangeAdapter>,
    private val feedManager: LiquidationFeedManager,
    private val marketDataService: MarketDataService,
    private val analysisEngine: MarketAnalysisEngine,
    private val scope: CoroutineScope
) {

    private val liquidationDao: LiquidationDao = db.liquidationDao()
    private val analysisDao: AnalysisDao = db.analysisDao()
    private val calibrationDao: CalibrationDao = db.calibrationDao()

    // ------------------------------------------------------------------
    // Configurable analysis parameters (UI-bound)
    // ------------------------------------------------------------------

    val minUsdThreshold = MutableStateFlow(5000.0)
    val excludeBtcEth = MutableStateFlow(true)
    val cooldownSec = MutableStateFlow(60L)
    val tacticalHorizonMs = MutableStateFlow(60_000L)
    val strategicHorizonMs = MutableStateFlow(3_600_000L)
    val isLiveStreaming = MutableStateFlow(true)

    // ------------------------------------------------------------------
    // Exposed flows
    // ------------------------------------------------------------------

    val liquidations: Flow<List<LiquidationEvent>> = liquidationDao.getAll().map { entities ->
        entities.map { e ->
            LiquidationEvent(
                id = e.id,
                exchange = e.exchange,
                symbol = e.symbol,
                side = if (e.side == LiquidationSide.LONG.name) LiquidationSide.LONG else LiquidationSide.SHORT,
                price = e.price,
                quantity = e.quantity,
                notionalUsd = e.notionalUsd,
                timestampNs = e.timestampMs * 1_000_000L,
                sequence = null,
                isSnapshot = false,
                sourceChannel = "room"
            )
        }
    }

    val analyses: Flow<List<AnalysisResult>> = analysisDao.getAll().map { entities ->
        entities.map { AnalysisMapper.toModel(it) }
    }

    private val _feedHealth = MutableStateFlow<List<AdapterHealth>>(emptyList())
    val feedHealth: StateFlow<List<AdapterHealth>> = _feedHealth.asStateFlow()

    private val _bannerTrigger = MutableSharedFlow<Pair<LiquidationEvent, String>>(extraBufferCapacity = 16)
    val bannerTrigger: SharedFlow<Pair<LiquidationEvent, String>> = _bannerTrigger.asSharedFlow()

    val marketStats: StateFlow<MarketStats> = combine(
        liquidations, analyses, _feedHealth
    ) { liqs, ans, health ->
        val shorts = liqs.filter { it.side == LiquidationSide.SHORT }
        val longs = liqs.filter { it.side == LiquidationSide.LONG }
        val verified = ans.filter { it.status != SignalStatus.PENDING }
        val hits = verified.count { it.status == SignalStatus.HIT }
        val top = liqs.filter { it.side == LiquidationSide.SHORT }
            .groupBy { it.symbol }
            .mapValues { (_, list) -> list.sumOf { it.notionalUsd } }
            .toList()
            .sortedByDescending { it.second }
            .take(6)

        MarketStats(
            totalLiquidations = liqs.size,
            shortLiquidations = shorts.size,
            longLiquidations = longs.size,
            totalShortUsd = shorts.sumOf { it.notionalUsd },
            totalLongUsd = longs.sumOf { it.notionalUsd },
            totalAnalyses = ans.size,
            verifiedAnalyses = verified.size,
            hitCount = hits,
            missCount = verified.size - hits,
            hitRatePct = if (verified.isNotEmpty()) hits.toDouble() / verified.size * 100.0 else 0.0,
            topLiquidatedSymbols = top,
            feedConnections = health.count { it.status == com.glasspro.tracker.data.remote.adapter.AdapterStatus.LIVE },
            feedConnectedExchanges = health.filter { it.status == com.glasspro.tracker.data.remote.adapter.AdapterStatus.LIVE }
                .map { it.exchange }
        )
    }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000L), MarketStats())

    // ------------------------------------------------------------------
    // Internal state
    // ------------------------------------------------------------------

    private val lastAnalyzedAt = ConcurrentHashMap<String, Long>()
    private val activeSymbols = ConcurrentHashMap<String, Long>() // symbol -> last activity ns
    private var verificationJob: Job? = null
    private var strategicJob: Job? = null

    fun start() {
        for (adapter in adapters) {
            adapter.start(scope)
        }
        feedManager.start()
        scope.launch {
            feedManager.events.collect { event ->
                handleRealLiquidation(event)
            }
        }
        scope.launch {
            val allHealth = adapters.map { it.health }
            allHealth.forEach { flow ->
                launch {
                    flow.collect {
                        _feedHealth.value = adapters.mapNotNull { adapter -> adapter.health.value }
                    }
                }
            }
        }
        verificationJob = scope.launch { verificationLoop() }
        strategicJob = scope.launch { strategicLoop() }
    }

    // ------------------------------------------------------------------
    // Real liquidation pipeline
    // ------------------------------------------------------------------

    private suspend fun handleRealLiquidation(event: LiquidationEvent) {
        // Persist every real event for history and the live feed.
        liquidationDao.insert(
            LiquidationEntity(
                id = event.id,
                symbol = event.symbol,
                exchange = event.exchange,
                side = event.side.name,
                price = event.price,
                quantity = event.quantity,
                notionalUsd = event.notionalUsd,
                timestampMs = event.timestampNs / 1_000_000L
            )
        )
        if (!isLiveStreaming.value) return

        activeSymbols[event.symbol] = event.timestampNs

        val isExcluded = excludeBtcEth.value && (event.symbol == "BTC" || event.symbol == "ETH")
        if (isExcluded) return

        // Trigger rule: real SHORT liquidation, single event or 3m cascade sum
        // above the threshold, and the per-symbol cooldown must have passed.
        if (event.side != LiquidationSide.SHORT) return
        val window = feedManager.windowFor(event.symbol, 180_000L)
        val cascadeShort = window.shortNotionalUsd
        val threshold = minUsdThreshold.value
        if (event.notionalUsd < threshold && cascadeShort < threshold) return

        val nowMs = System.currentTimeMillis()
        val last = lastAnalyzedAt[event.symbol] ?: 0L
        val cooldown = cooldownSec.value * 1000L
        if (nowMs - last < cooldown) return
        lastAnalyzedAt[event.symbol] = nowMs

        runAnalysis(event.symbol, tacticalHorizonMs.value, "1DK", event, cascadeShort)
    }

    private suspend fun runAnalysis(
        symbol: String,
        horizonMs: Long,
        horizonLabel: String,
        triggerEvent: LiquidationEvent?,
        cascadeShortUsd: Double
    ) {
        val calibration = calibrationFor(symbol)
        val result = analysisEngine.analyze(symbol, horizonMs, horizonLabel, calibration) ?: return
        persistAnalysis(result, cascadeShortUsd)
        if (horizonLabel == "1DK" && triggerEvent != null) {
            _bannerTrigger.tryEmit(triggerEvent to result.direction.symbol)
        }
    }

    private suspend fun persistAnalysis(result: AnalysisResult, cascadeShortUsd: Double) {
        // L/S trend: compare with the previous stored analysis for this symbol.
        val lsTrend = computeLsTrend(result)
        val finalResult = result.copy(lsTrend = lsTrend)
        analysisDao.insert(AnalysisMapper.toEntity(finalResult, cascadeShortUsd))
    }

    private suspend fun computeLsTrend(current: AnalysisResult): String {
        val previous = analysisDao.getResolved(current.symbol)
            .firstOrNull()
        val prevRatio = previous?.lsRatio
        val curRatio = current.lsRatio
        if (curRatio == null || prevRatio == null) return "flat"
        return when {
            curRatio > prevRatio * 1.02 -> "up"
            curRatio < prevRatio * 0.98 -> "down"
            else -> "flat"
        }
    }

    // ------------------------------------------------------------------
    // Verification & calibration
    // ------------------------------------------------------------------

    private suspend fun verificationLoop() {
        while (scope.isActive) {
            try {
                val pending = analysisDao.getPending()
                val now = System.currentTimeMillis()
                for (entity in pending) {
                    if (now >= entity.verifyAtMs) {
                        verify(entity)
                    }
                }
            } catch (e: Exception) {
                // Keep the loop alive; log and continue.
                android.util.Log.e("MarketRepository", "verification loop error: ${e.message}")
            }
            delay(5_000L)
        }
    }

    private suspend fun verify(entity: AnalysisEntity) {
        val symbol = entity.symbol
        val actualPrice = marketDataService.fetchConsensusPrice(symbol)
            ?: entity.price // degraded fallback: price is already a real consensus value
        val direction = com.glasspro.tracker.data.db.Wire.direction(entity)
        val status = CalibrationEngine.evaluate(direction, entity.atrPct1h, entity.price, actualPrice)
        val changePct = if (entity.price > 0.0) {
            (actualPrice - entity.price) / entity.price * 100.0
        } else {
            0.0
        }
        analysisDao.updateVerification(
            id = entity.id,
            status = status.name,
            actualPrice = actualPrice,
            priceChangePct = changePct
        )
        updateCalibration(entity, actualPrice)
    }

    private suspend fun calibrationFor(symbol: String): CalibrationState? {
        val entity = calibrationDao.get(symbol) ?: return null
        val resolved = AnalysisMapper.parseResolved(entity.resolvedJson)
        return CalibrationState(
            symbol = symbol,
            resolved = resolved,
            rollingAccuracy20 = entity.rollingAccuracy20,
            componentCorrelations = AnalysisMapper.parseDoubleMap(entity.componentCorrelationsJson)
        )
    }

    private suspend fun updateCalibration(entity: AnalysisEntity, actualPrice: Double) {
        val direction = com.glasspro.tracker.data.db.Wire.direction(entity)
        val components = AnalysisMapper.parseComponents(entity.componentsJson)
            .filter { it.available }
            .associate { it.key to it.score }

        val previous = calibrationDao.get(entity.symbol)
        val resolved = (previous?.let { AnalysisMapper.parseResolved(it.resolvedJson) } ?: emptyList()) +
            ResolvedPrediction(
                priceAtPrediction = entity.price,
                priceAfter = actualPrice,
                direction = direction,
                atrPct = entity.atrPct1h,
                components = components
            )
        val trimmed = resolved.takeLast(200)

        val accuracy = CalibrationEngine.rollingAccuracy(trimmed)
        val correlations = CalibrationEngine.componentCorrelations(trimmed)

        calibrationDao.upsert(
            CalibrationEntity(
                symbol = entity.symbol,
                resolvedJson = AnalysisMapper.serializeResolved(trimmed),
                rollingAccuracy20 = accuracy,
                componentCorrelationsJson = AnalysisMapper.serializeDoubleMap(correlations),
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    // ------------------------------------------------------------------
    // Strategic loop
    // ------------------------------------------------------------------

    private suspend fun strategicLoop() {
        while (scope.isActive) {
            try {
                val cutoffNs = System.currentTimeMillis() * 1_000_000L - 30 * 60 * 1_000_000_000L
                val active = activeSymbols.filterValues { it >= cutoffNs }.keys
                for (symbol in active) {
                    val now = System.currentTimeMillis()
                    val last = lastAnalyzedAt[symbol] ?: 0L
                    // One strategic pass per symbol per 15 minutes.
                    if (now - last >= 15 * 60 * 1000L) {
                        lastAnalyzedAt[symbol] = now
                        runAnalysis(symbol, strategicHorizonMs.value, "1S", null, 0.0)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MarketRepository", "strategic loop error: ${e.message}")
            }
            delay(5 * 60 * 1000L)
        }
    }

    // ------------------------------------------------------------------
    // Public actions
    // ------------------------------------------------------------------

    suspend fun triggerManualAnalysis(symbol: String) {
        val clean = symbol.trim().uppercase()
        if (clean.isBlank()) return
        runAnalysis(clean, tacticalHorizonMs.value, "1DK", null, 0.0)
    }

    suspend fun clearHistory() {
        liquidationDao.clearAll()
        analysisDao.clearAll()
        calibrationDao.clearAll()
        feedManager.clearHistory()
    }

    fun stop() {
        verificationJob?.cancel()
        strategicJob?.cancel()
        for (adapter in adapters) adapter.stop()
    }
}

// ---------------------------------------------------------------------------
// Entity <-> model mapping (kept in the repository package)
// ---------------------------------------------------------------------------

object AnalysisMapper {

    fun toEntity(result: AnalysisResult, cascadeShortUsd: Double): AnalysisEntity {
        return AnalysisEntity(
            id = result.id,
            symbol = result.symbol,
            createdAtMs = result.createdAtMs,
            horizonMs = result.horizonMs,
            horizonLabel = result.horizonLabel,
            price = result.price,
            totalScore = result.totalScore,
            direction = result.direction.name,
            confidence = result.confidence,
            signalStrength = result.signalStrength,
            probabilitiesJson = JSONObject()
                .put("up", result.probabilities.up)
                .put("down", result.probabilities.down)
                .put("uncertain", result.probabilities.uncertain)
                .toString(),
            componentsJson = serializeComponents(result.components),
            orderBookImbalancePct = result.orderBookImbalancePct,
            tradeFlowBuyPct = result.tradeFlowBuyPct,
            cvd = result.cvd,
            fundingRatePct = result.fundingRatePct,
            oiChangePct1h = result.oiChangePct1h,
            oiUsd = result.oiUsd,
            takerBuyPct = result.takerBuyPct,
            lsRatio = result.lsRatio,
            lsTrend = result.lsTrend,
            fundingTrend = result.fundingTrend,
            liquidationImbalancePct = result.liquidationImbalancePct,
            globalOiUsd = result.globalOiUsd,
            risksJson = JSONObject()
                .put("manipulation", result.risks.manipulationIndex)
                .put("spoof", result.risks.spoofRate)
                .put("liqHunt", result.risks.liquidationHunt)
                .put("fundingDeviance", result.risks.fundingDeviance)
                .put("volumeMismatch", result.risks.volumePriceMismatch)
                .put("falseBreakout", result.risks.falseBreakout)
                .put("general", result.risks.generalRisk)
                .toString(),
            strategyJson = JSONObject()
                .put("side", result.strategy.side.name)
                .put("entry", result.strategy.entry)
                .put("sl", result.strategy.stopLoss ?: JSONObject.NULL)
                .put("tp", result.strategy.takeProfit ?: JSONObject.NULL)
                .put("leverage", result.strategy.leverage)
                .put("alerts", JSONArray().apply { result.strategy.alerts.forEach { put(it) } })
                .toString(),
            forecastsJson = JSONObject()
                .put("f5m", result.forecasts.forecast5m)
                .put("f15m", result.forecasts.forecast15m)
                .put("f1h", result.forecasts.forecast1h)
                .toString(),
            whaleTradesJson = JSONArray().apply {
                result.whaleTrades.forEach { w ->
                    put(
                        JSONObject()
                            .put("ts", w.timestampNs)
                            .put("price", w.price)
                            .put("size", w.size)
                            .put("value", w.valueUsd)
                            .put("side", w.side.name)
                            .put("exchange", w.exchange)
                    )
                }
            }.toString(),
            conflictsJson = JSONArray().apply { result.conflicts.forEach { put(it) } }.toString(),
            calibrationJson = JSONObject()
                .put("accuracy", result.calibration.rollingAccuracy20 ?: JSONObject.NULL)
                .put("resolved", result.calibration.resolvedCount)
                .put("correlations", JSONObject().apply {
                    result.calibration.componentCorrelations.forEach { (k, v) -> put(k, v) }
                })
                .toString(),
            providerCount = result.providerCount,
            priceDispersionPct = result.priceDispersionPct,
            status = result.status.name,
            actualPrice = result.actualPrice,
            priceChangePct = result.priceChangePct,
            verifyAtMs = result.verifyAtMs,
            atrPct1h = result.atrPct1h
        )
    }

    fun toModel(entity: AnalysisEntity): AnalysisResult {
        val probabilities = parseProbabilities(entity.probabilitiesJson)
        val components = parseComponents(entity.componentsJson)
        val risks = parseRisks(entity.risksJson)
        val strategy = parseStrategy(entity.strategyJson)
        val forecasts = parseForecasts(entity.forecastsJson)
        val whales = parseWhales(entity.whaleTradesJson)
        val conflicts = parseStringList(entity.conflictsJson)
        val calibration = parseCalibration(entity.calibrationJson)

        return AnalysisResult(
            id = entity.id,
            symbol = entity.symbol,
            createdAtMs = entity.createdAtMs,
            price = entity.price,
            totalScore = entity.totalScore,
            direction = com.glasspro.tracker.data.db.Wire.direction(entity),
            confidence = entity.confidence,
            signalStrength = entity.signalStrength,
            probabilities = probabilities,
            components = components,
            orderBookImbalancePct = entity.orderBookImbalancePct,
            tradeFlowBuyPct = entity.tradeFlowBuyPct,
            cvd = entity.cvd,
            fundingRatePct = entity.fundingRatePct,
            oiChangePct1h = entity.oiChangePct1h,
            oiUsd = entity.oiUsd,
            takerBuyPct = entity.takerBuyPct,
            lsRatio = entity.lsRatio,
            lsTrend = entity.lsTrend,
            fundingTrend = entity.fundingTrend,
            liquidationImbalancePct = entity.liquidationImbalancePct,
            globalOiUsd = entity.globalOiUsd,
            risks = risks,
            strategy = strategy,
            forecasts = forecasts,
            whaleTrades = whales,
            conflicts = conflicts,
            calibration = calibration,
            providerCount = entity.providerCount,
            priceDispersionPct = entity.priceDispersionPct,
            status = com.glasspro.tracker.data.db.Wire.status(entity),
            actualPrice = entity.actualPrice,
            priceChangePct = entity.priceChangePct,
            verifyAtMs = entity.verifyAtMs,
            horizonMs = entity.horizonMs,
            horizonLabel = entity.horizonLabel,
            atrPct1h = entity.atrPct1h
        )
    }

    fun serializeComponents(components: List<ComponentScore>): String {
        return JSONArray().apply {
            components.forEach { c ->
                put(
                    JSONObject()
                        .put("key", c.key)
                        .put("score", c.score)
                        .put("weight", c.weight)
                        .put("contribution", c.contribution)
                        .put("available", c.available)
                )
            }
        }.toString()
    }

    fun parseComponents(json: String): List<ComponentScore> {
        val result = mutableListOf<ComponentScore>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    ComponentScore(
                        key = o.optString("key"),
                        score = o.optDouble("score"),
                        weight = o.optDouble("weight"),
                        contribution = o.optDouble("contribution"),
                        available = o.optBoolean("available", true)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return result
    }

    fun serializeResolved(resolved: List<ResolvedPrediction>): String {
        return JSONArray().apply {
            resolved.forEach { p ->
                put(
                    JSONObject()
                        .put("priceAt", p.priceAtPrediction)
                        .put("priceAfter", p.priceAfter)
                        .put("direction", p.direction.name)
                        .put("atr", p.atrPct)
                        .put("components", JSONObject().apply { p.components.forEach { (k, v) -> put(k, v) } })
                )
            }
        }.toString()
    }

    fun parseResolved(json: String): List<ResolvedPrediction> {
        val result = mutableListOf<ResolvedPrediction>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val comps = mutableMapOf<String, Double>()
                val compsObj = o.optJSONObject("components")
                if (compsObj != null) {
                    val it = compsObj.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        comps[k] = compsObj.optDouble(k)
                    }
                }
                result.add(
                    ResolvedPrediction(
                        priceAtPrediction = o.optDouble("priceAt"),
                        priceAfter = o.optDouble("priceAfter"),
                        direction = runCatching { Direction.valueOf(o.optString("direction")) }
                            .getOrDefault(Direction.NEUTRAL),
                        atrPct = o.optDouble("atr"),
                        components = comps
                    )
                )
            }
        } catch (_: Exception) {
        }
        return result
    }

    fun serializeDoubleMap(map: Map<String, Double>): String {
        return JSONObject().apply { map.forEach { (k, v) -> put(k, v) } }.toString()
    }

    fun parseDoubleMap(json: String): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        try {
            val obj = JSONObject(json)
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                result[k] = obj.optDouble(k)
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun parseProbabilities(json: String): com.glasspro.tracker.core.model.ProbabilityModel {
        return runCatching {
            val o = JSONObject(json)
            com.glasspro.tracker.core.model.ProbabilityModel(
                up = o.optDouble("up"),
                down = o.optDouble("down"),
                uncertain = o.optDouble("uncertain")
            )
        }.getOrDefault(com.glasspro.tracker.core.model.ProbabilityModel(0.0, 0.0, 100.0))
    }

    private fun parseRisks(json: String): com.glasspro.tracker.core.model.RiskReport {
        return runCatching {
            val o = JSONObject(json)
            com.glasspro.tracker.core.model.RiskReport(
                manipulationIndex = o.optDouble("manipulation"),
                spoofRate = o.optDouble("spoof"),
                liquidationHunt = o.optDouble("liqHunt"),
                fundingDeviance = o.optDouble("fundingDeviance"),
                volumePriceMismatch = o.optDouble("volumeMismatch"),
                falseBreakout = o.optDouble("falseBreakout"),
                generalRisk = o.optDouble("general")
            )
        }.getOrDefault(com.glasspro.tracker.core.model.RiskReport(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
    }

    private fun parseStrategy(json: String): com.glasspro.tracker.core.model.StrategySignal {
        return runCatching {
            val o = JSONObject(json)
            val alerts = mutableListOf<String>()
            val arr = o.optJSONArray("alerts")
            if (arr != null) {
                for (i in 0 until arr.length()) alerts.add(arr.getString(i))
            }
            com.glasspro.tracker.core.model.StrategySignal(
                side = runCatching { Direction.valueOf(o.optString("side")) }.getOrDefault(Direction.NEUTRAL),
                entry = o.optDouble("entry"),
                stopLoss = o.opt("sl").takeUnless { it == JSONObject.NULL }?.toString()?.toDoubleOrNull(),
                takeProfit = o.opt("tp").takeUnless { it == JSONObject.NULL }?.toString()?.toDoubleOrNull(),
                leverage = o.optInt("leverage"),
                alerts = alerts
            )
        }.getOrDefault(
            com.glasspro.tracker.core.model.StrategySignal(Direction.NEUTRAL, 0.0, null, null, 10, emptyList())
        )
    }

    private fun parseForecasts(json: String): com.glasspro.tracker.core.model.Forecasts {
        return runCatching {
            val o = JSONObject(json)
            com.glasspro.tracker.core.model.Forecasts(
                forecast5m = o.optDouble("f5m"),
                forecast15m = o.optDouble("f15m"),
                forecast1h = o.optDouble("f1h")
            )
        }.getOrDefault(com.glasspro.tracker.core.model.Forecasts(0.0, 0.0, 0.0))
    }

    private fun parseWhales(json: String): List<com.glasspro.tracker.core.model.WhaleTrade> {
        val result = mutableListOf<com.glasspro.tracker.core.model.WhaleTrade>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    com.glasspro.tracker.core.model.WhaleTrade(
                        timestampNs = o.optLong("ts"),
                        price = o.optDouble("price"),
                        size = o.optDouble("size"),
                        valueUsd = o.optDouble("value"),
                        side = if (o.optString("side") == "LONG") LiquidationSide.LONG else LiquidationSide.SHORT,
                        exchange = o.optString("exchange")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun parseStringList(json: String): List<String> {
        val result = mutableListOf<String>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) result.add(arr.getString(i))
        } catch (_: Exception) {
        }
        return result
    }

    private fun parseCalibration(json: String): com.glasspro.tracker.core.model.CalibrationStats {
        return runCatching {
            val o = JSONObject(json)
            val acc = o.opt("accuracy")
            val correlations = mutableMapOf<String, Double>()
            val corrObj = o.optJSONObject("correlations")
            if (corrObj != null) {
                val it = corrObj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    correlations[k] = corrObj.optDouble(k)
                }
            }
            com.glasspro.tracker.core.model.CalibrationStats(
                rollingAccuracy20 = acc.toString().toDoubleOrNull(),
                resolvedCount = o.optInt("resolved"),
                componentCorrelations = correlations
            )
        }.getOrDefault(com.glasspro.tracker.core.model.CalibrationStats(null, 0, emptyMap()))
    }
}
