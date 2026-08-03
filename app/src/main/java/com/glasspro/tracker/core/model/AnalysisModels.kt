package com.glasspro.tracker.core.model

/**
 * Output models produced by the analysis, risk and strategy engines.
 * These are the typed contracts rendered by the UI and persisted in Room.
 */

enum class Direction(val label: String, val symbol: String) {
    LONG("YUKARI", "▲"),
    SHORT("AŞAĞI", "▼"),
    NEUTRAL("YATAY", "◆");

    companion object {
        fun fromScore(score: Double, threshold: Double = 20.0): Direction =
            when {
                score > threshold -> LONG
                score < -threshold -> SHORT
                else -> NEUTRAL
            }
    }
}

enum class SignalStatus(val label: String) {
    PENDING("BEKLİYOR"),
    HIT("HİT"),
    MISS("MİSS");
}

/**
 * Result of the technical indicator suite on one timeframe.
 */
data class IndicatorResult(
    val timeframe: String,
    val rsi: Double,
    val emaState: String,          // Bull | Bear | Mixed
    val macdHistogram: Double,
    val atrPct: Double,
    val stochK: Double,
    val bollingerBPct: Double,
    val volumeRatio: Double,
    val retPct1: Double,
    val retPct3: Double,
    val compositeScore: Double     // -100..100 technical score on this timeframe
)

/**
 * One scored component of the nine-component model.
 */
data class ComponentScore(
    val key: String,               // Order Book, Trade Flow, Open Interest, ...
    val score: Double,             // -100..100 raw component score
    val weight: Double,            // effective (renormalized) weight
    val contribution: Double,      // score * weight
    val available: Boolean
)

/**
 * Manipulation and risk radar output.
 */
data class RiskReport(
    val manipulationIndex: Double,  // 0..100
    val spoofRate: Double,          // 0..100
    val liquidationHunt: Double,    // 0..100
    val fundingDeviance: Double,    // 0..100
    val volumePriceMismatch: Double,// 0..100
    val falseBreakout: Double,      // 0..100
    val generalRisk: Double         // 0..100
)

/**
 * ATR-based position strategy.
 */
data class StrategySignal(
    val side: Direction,
    val entry: Double,
    val stopLoss: Double?,
    val takeProfit: Double?,
    val leverage: Int,
    val alerts: List<String>
)

/**
 * Up / down / uncertain probability distribution.
 */
data class ProbabilityModel(
    val up: Double,
    val down: Double,
    val uncertain: Double
)

/**
 * Forecasts produced as weighted blends of the component scores.
 */
data class Forecasts(
    val forecast5m: Double,
    val forecast15m: Double,
    val forecast1h: Double
)

/**
 * Calibration statistics maintained by the verification loop.
 */
data class CalibrationStats(
    val rollingAccuracy20: Double?,
    val resolvedCount: Int,
    val componentCorrelations: Map<String, Double>
)

/**
 * One resolved prediction used by the calibration loop: the realized price
 * change after the horizon plus the per-component scores at prediction time.
 */
data class ResolvedPrediction(
    val priceAtPrediction: Double,
    val priceAfter: Double,
    val direction: Direction,
    val atrPct: Double,
    val components: Map<String, Double>
)

/**
 * Per-symbol calibration state loaded from the database and passed into the
 * analysis engine so every new analysis embeds the latest statistics.
 */
data class CalibrationState(
    val symbol: String,
    val resolved: List<ResolvedPrediction>,
    val rollingAccuracy20: Double?,
    val componentCorrelations: Map<String, Double>
)

/**
 * Full analysis result for one symbol at one point in time.
 */
data class AnalysisResult(
    val id: String,
    val symbol: String,
    val createdAtMs: Long,
    val price: Double,
    val totalScore: Double,
    val direction: Direction,
    val confidence: Double,
    val signalStrength: Double,
    val probabilities: ProbabilityModel,
    val components: List<ComponentScore>,
    val orderBookImbalancePct: Double,
    val tradeFlowBuyPct: Double,
    val cvd: Double,
    val fundingRatePct: Double,
    val oiChangePct1h: Double?,
    val oiUsd: Double?,
    val takerBuyPct: Double,
    val lsRatio: Double?,
    val lsTrend: String,
    val fundingTrend: String,
    val liquidationImbalancePct: Double,
    val globalOiUsd: Double?,
    val risks: RiskReport,
    val strategy: StrategySignal,
    val forecasts: Forecasts,
    val whaleTrades: List<WhaleTrade>,
    val conflicts: List<String>,
    val calibration: CalibrationStats,
    val providerCount: Int,
    val priceDispersionPct: Double,
    val status: SignalStatus,
    val actualPrice: Double?,
    val priceChangePct: Double?,
    val verifyAtMs: Long,
    val horizonMs: Long,
    val horizonLabel: String,
    val atrPct1h: Double
)

/**
 * Whale trade extracted from the consolidated trade stream.
 */
data class WhaleTrade(
    val timestampNs: Long,
    val price: Double,
    val size: Double,
    val valueUsd: Double,
    val side: LiquidationSide,
    val exchange: String
)

/**
 * Aggregated statistics for the dashboard.
 */
data class MarketStats(
    val totalLiquidations: Int = 0,
    val shortLiquidations: Int = 0,
    val longLiquidations: Int = 0,
    val totalShortUsd: Double = 0.0,
    val totalLongUsd: Double = 0.0,
    val totalAnalyses: Int = 0,
    val verifiedAnalyses: Int = 0,
    val hitCount: Int = 0,
    val missCount: Int = 0,
    val hitRatePct: Double = 0.0,
    val topLiquidatedSymbols: List<Pair<String, Double>> = emptyList(),
    val feedConnections: Int = 0,
    val feedConnectedExchanges: List<String> = emptyList()
)
