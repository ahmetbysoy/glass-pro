package com.glasspro.tracker.data.remote.adapter

import java.util.Locale

/**
 * Normalizes exchange-specific instrument ids to a single base symbol.
 *
 * Examples:
 *   BTCUSDT        -> BTC
 *   BTC-USDT-SWAP  -> BTC
 *   BTC_USDT       -> BTC
 *   1000PEPEUSDT   -> 1000PEPE
 *   PF_XBTUSD      -> BTC
 */
object SymbolNormalizer {

    fun toBase(raw: String): String {
        var s = raw.trim().uppercase(Locale.US)
        s = s.replace("-USDT-SWAP", "")
            .replace("-USDT-PERP", "")
            .replace("-USDT", "")
            .replace("USDT", "")
            .replace("_USDT", "")
            .replace("-PERP", "")
            .replace("-USD", "")
            .replace("USD", "")
        // Kraken style PF_XBTUSD -> XBT
        if (s.startsWith("PF_")) s = s.removePrefix("PF_")
        if (s.endsWith("USD")) s = s.dropLast(3)
        return s
    }

    /** OKX instrument id for a perpetual swap. */
    fun okxSwap(symbol: String): String = "${symbol.uppercase()}-USDT-SWAP"

    /** OKX underlyng (uly) for liquidation subscription. */
    fun okxUly(symbol: String): String = "${symbol.uppercase()}-USDT"

    /** Binance/Bybit/Bitget flat futures symbol. */
    fun flat(symbol: String): String = "${symbol.uppercase()}USDT"

    /** Gate perpetual contract name. */
    fun gatePerp(symbol: String): String = "${symbol.uppercase()}_USDT"

    /** Hyperliquid coin name. */
    fun hyperliquidCoin(symbol: String): String = symbol.uppercase()
}
