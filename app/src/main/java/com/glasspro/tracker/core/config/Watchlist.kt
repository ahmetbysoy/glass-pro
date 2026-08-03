package com.glasspro.tracker.core.config

/**
 * Default watchlist of real, highly liquid USDT perpetual instruments listed
 * on every tracked venue. These are exchange-native instruments — no
 * invented symbols. The list is user-editable through the settings screen in
 * future iterations; today it drives the liquidation subscriptions and the
 * strategic analysis scheduler.
 */
object Watchlist {
    val defaultSymbols: List<String> = listOf(
        "BTC", "ETH", "SOL", "XRP", "DOGE",
        "PEPE", "WIF", "BONK", "SUI", "TIA",
        "SEI", "INJ", "ARB", "OP", "LTC",
        "BCH", "NEAR", "ATOM", "AVAX", "LINK",
        "ADA", "DOT", "UNI", "APT", "TRX",
        "FIL", "ICP", "SHIB", "ENA", "JUP"
    )
}
