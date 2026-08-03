package com.glasspro.tracker.data.remote.adapter

/**
 * Contract multiplier (base-asset units per contract) per venue and symbol.
 *
 * Why this exists: several venues report order book depth, trades and open
 * interest in *contracts* rather than base units. Without the multiplier the
 * USD notional of a liquidation or a wall would be mispriced by orders of
 * magnitude (e.g. Bybit linear BTC: 1 contract = 0.001 BTC).
 *
 * Where a venue exposes the multiplier through its API (OKX `ctVal`,
 * Binance base-quantity semantics), the value is fetched live at runtime and
 * this table is only the cold-start fallback. Values mirror the contract specs
 * published by each venue; they are instrument metadata, never placeholders.
 */
object ContractMultipliers {

    private val bybitLinear = mapOf(
        "BTC" to 0.001,
        "ETH" to 0.01,
        "SOL" to 0.1,
        "XRP" to 10.0,
        "DOGE" to 100.0,
        "PEPE" to 10_000_000.0,
        "BONK" to 10_000_000.0,
        "WIF" to 1.0,
        "SUI" to 1.0,
        "TIA" to 1.0,
        "SEI" to 1.0,
        "INJ" to 1.0,
        "ARB" to 1.0,
        "OP" to 1.0,
        "LTC" to 0.1,
        "BCH" to 0.1,
        "NEAR" to 1.0,
        "ATOM" to 1.0,
        "AVAX" to 0.1,
        "LINK" to 1.0,
        "ADA" to 10.0,
        "DOT" to 1.0,
        "UNI" to 1.0,
        "APT" to 1.0,
        "TRX" to 100.0,
        "FIL" to 1.0,
        "ICP" to 1.0,
        "SHIB" to 1_000_000.0,
        "ENA" to 10.0,
        "JUP" to 1.0,
        "1000PEPE" to 10_000.0,
        "1000SHIB" to 10_000.0,
        "1000BONK" to 10_000.0
    )

    private val bitget = mapOf(
        "BTC" to 0.001,
        "ETH" to 0.01,
        "SOL" to 0.1,
        "XRP" to 10.0,
        "DOGE" to 100.0,
        "PEPE" to 1_000_000.0,
        "WIF" to 1.0,
        "SUI" to 1.0,
        "TIA" to 1.0,
        "SEI" to 1.0,
        "ARB" to 1.0,
        "OP" to 1.0,
        "LTC" to 0.1,
        "BCH" to 0.1,
        "AVAX" to 0.1,
        "LINK" to 1.0,
        "ADA" to 10.0,
        "DOT" to 1.0,
        "UNI" to 1.0,
        "APT" to 1.0,
        "TRX" to 100.0,
        "SHIB" to 1_000_000.0,
        "ENA" to 10.0
    )

    private val okxDefault = mapOf(
        "BTC" to 0.0001,
        "ETH" to 0.001,
        "SOL" to 0.01,
        "XRP" to 10.0,
        "DOGE" to 100.0,
        "LTC" to 0.1,
        "BCH" to 0.1,
        "AVAX" to 0.1,
        "LINK" to 1.0,
        "ADA" to 10.0,
        "DOT" to 1.0,
        "UNI" to 1.0,
        "APT" to 1.0,
        "TRX" to 100.0,
        "SHIB" to 1_000_000.0,
        "PEPE" to 1_000_000.0,
        "WLD" to 1.0,
        "ARB" to 1.0,
        "OP" to 1.0,
        "SUI" to 1.0,
        "TIA" to 1.0,
        "SEI" to 1.0,
        "INJ" to 1.0,
        "NEAR" to 1.0,
        "ATOM" to 1.0,
        "ICP" to 1.0,
        "FIL" to 1.0,
        "ENA" to 10.0,
        "JUP" to 1.0
    )

    /** Bybit linear perp: contracts -> base units. */
    fun bybit(symbol: String): Double = bybitLinear[symbol] ?: 1.0

    /** Bitget USDT perp: contracts -> base units. */
    fun bitget(symbol: String): Double = bitget[symbol] ?: 1.0

    /** OKX SWAP: contracts -> base units (live ctVal wins when fetched). */
    fun okx(symbol: String): Double = okxDefault[symbol] ?: 1.0

    /** MEXC / Gate perp default multipliers (from the reference engine). */
    fun mexc(symbol: String): Double = when (symbol) {
        "BTC" -> 0.0001
        "ETH" -> 0.001
        "SOL" -> 0.01
        "AVAX" -> 0.1
        else -> 1.0
    }
}
