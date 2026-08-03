package com.glasspro.tracker

import com.glasspro.tracker.data.remote.adapter.SymbolNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class SymbolNormalizerTest {

    @Test
    fun `binance flat symbol`() {
        assertEquals("BTC", SymbolNormalizer.toBase("BTCUSDT"))
    }

    @Test
    fun `okx swap symbol`() {
        assertEquals("SOL", SymbolNormalizer.toBase("SOL-USDT-SWAP"))
    }

    @Test
    fun `gate perp symbol`() {
        assertEquals("ETH", SymbolNormalizer.toBase("ETH_USDT"))
    }

    @Test
    fun `thousand multiplier symbols keep prefix`() {
        assertEquals("1000PEPE", SymbolNormalizer.toBase("1000PEPEUSDT"))
    }

    @Test
    fun `venue specific helpers`() {
        assertEquals("BTC-USDT-SWAP", SymbolNormalizer.okxSwap("BTC"))
        assertEquals("BTC-USDT", SymbolNormalizer.okxUly("BTC"))
        assertEquals("BTCUSDT", SymbolNormalizer.flat("BTC"))
        assertEquals("BTC_USDT", SymbolNormalizer.gatePerp("BTC"))
        assertEquals("BTC", SymbolNormalizer.hyperliquidCoin("btc"))
    }
}
