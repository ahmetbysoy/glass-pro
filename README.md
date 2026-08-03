# GlassPro — Gerçek Piyasa Likidasyon Takip & Profesyonel Analiz Motoru

Android (Kotlin + Jetpack Compose) uygulaması. **Simülasyon yok, mock veri yok,
yer tutucu yok**: canlı akıştaki her likidasyon olayı ve her analiz girdisi
halka açık borsa API'lerinden / WebSocket'lerinden gelen gerçek veridir.

## Veri Kaynakları (tamamı public, API key gerektirmez)

| Borsa | Likidasyon | Fiyat/Market Verisi |
|---|---|---|
| Binance (USD-M) | `!forceOrder@arr` WSS + `fapi/v1/allForceOrders` REST | premiumIndex, OI + 5m geçmiş, takerlongshortRatio, klines, depth, aggTrades |
| OKX (SWAP) | `liquidation-orders` WSS + `public/liquidation-orders` REST | ticker, funding-rate, open-interest, instruments (ctVal), candles, books, trades, Rubik L/S |
| Bybit (Linear) | `v5/market/liquidation` REST (10sn poll) | tickers, funding, OI + 5m geçmiş, account-ratio, kline, orderbook, recent-trade |
| Bitget (USDT-F) | — | ticker, current-fund-rate, open-interest, orderbook, fills, candles |
| Hyperliquid | — | metaAndAssetCtxs, l2Book, recentTrades |
| Gate (USDT Perp) | — | tickers, contracts (funding/OI), order_book, trades, candlesticks |

Ek: Deribit DVOL (BTC/ETH opsiyon bileşeni), Crypto Fear & Greed (makro bias).

## Mimari

```
com.glasspro.tracker
├── core
│   ├── math          → Statistics (medyan, MAD, robust z-skor, Pearson, EWMA), TechnicalIndicators (RSI/EMA/MACD/ATR/Stoch/BB)
│   ├── model         → likidasyon, analiz, risk, strateji, kalibrasyon modelleri
│   └── util          → thread-safe deduplicator
├── data
│   ├── remote
│   │   ├── ws        → WebSocketClient (reconnect/backoff/jitter/heartbeat/staleness state machine)
│   │   ├── rest      → RestClient (retry + backoff)
│   │   └── adapter   → Binance/OKX/Bybit/Bitget/Hyperliquid/Gate adapters
│   ├── engine        → MarketAnalysisEngine (9 bileşen), OrderBookAnalytics, TradeFlowAnalytics,
│   │                   DerivativeAnalytics, RiskEngine, StrategyEngine, CalibrationEngine
│   └── repository    → MarketRepository (orkestrasyon + doğrulama döngüsü)
└── ui                → 4 sekme: Canlı Akış / Tahminler / İstatistik / Ayarlar
```

## Analiz Motoru (9 bileşen)

| Bileşen | Ağırlık | Girdi |
|---|---|---|
| Emir Defteri | %15 | bid/ask dengesi, whale wall'lar, spoof oranı |
| Trade Akışı | %15 | CVD, taker oranı, fiyat/CVD diverjansı |
| Açık Pozisyon | %12 | OI 1s değişimi × fiyat yönü |
| Likidasyon | %12 | short/long tasfiye dengesi (3dk pencere) |
| Momentum | %10 | 5m/15m/1H/4H indikatör blend + Fear&Greed |
| Opsiyonlar | %10 | Deribit DVOL dinamikleri |
| Balina Net Akışı | %10 | balina trade'leri |
| Funding | %8 | aşırı funding tespiti (squeeze) |
| Hacim | %8 | hacim rejimi |

- Dinamik ağırlık kaydırma: düşük likiditede Emir Defteri + Trade Akışı,
  yüksek volatilitede Hacim + Momentum güçlendirilir.
- Veri olmayan bileşen ağırlık yeniden normalizasyonuyla hariç tutulur
  (uydurma veri asla üretilmez).
- Quant Security Layer: medyandan >%2 sapan borsa fiyatı reddedilir
  (MAD z-skor yedeğiyle).
- Strateji: ATR-tabanlı SL/TP, kaldıraç önerisi, funding/taker alarmları.
- Kalibrasyon: rolling-20 isabet oranı + bileşen-fiyat Pearson korelasyonları.

## Tetikleme & Doğrulama

- **Taktik (1DK):** gerçek short likidasyon ≥ eşik (varsayılan $5K) VEYA
  3 dk'lık kaskad toplamı ≥ eşik olduğunda otomatik analiz.
- **Stratejik (1S):** son 30 dk'da likidasyon aktivitesi olan semboller için
  15 dk'da bir 1 saatlik analiz.
- Doğrulama: ATR-relative flat band ile HİT/MİSS; sonuçlar Room'da saklanır.

## Derleme

```bash
# Android Studio (Electric Eel+) ile açın veya:
./gradlew assembleDebug
./gradlew test                # JVM birim testleri
```

Gereksinimler: JDK 17+, Android SDK 36. `debug.keystore` üretilmemişse debug
signing için kendi keystore'unuzu oluşturun:

```bash
keytool -genkeypair -v -keystore debug.keystore -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android \
  -dname "CN=Android Debug,O=Android,C=US"
```
