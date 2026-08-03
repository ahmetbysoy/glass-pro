package com.glasspro.tracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasspro.tracker.core.model.AnalysisResult
import com.glasspro.tracker.core.model.MarketStats
import com.glasspro.tracker.core.model.SignalStatus
import com.glasspro.tracker.ui.theme.ElectricCyan
import com.glasspro.tracker.ui.theme.ElectricCyanBg
import com.glasspro.tracker.ui.theme.NeonAmber
import com.glasspro.tracker.ui.theme.NeonGreen
import com.glasspro.tracker.ui.theme.NeonGreenBg
import com.glasspro.tracker.ui.theme.NeonRed
import com.glasspro.tracker.ui.theme.SlateBorder
import com.glasspro.tracker.ui.theme.SlateCard
import com.glasspro.tracker.ui.theme.SlateDark
import com.glasspro.tracker.ui.theme.SlateSurface
import com.glasspro.tracker.ui.theme.TextMuted
import com.glasspro.tracker.ui.theme.TextPrimary
import com.glasspro.tracker.ui.theme.TextSecondary

@Composable
fun AnalyticsTab(
    analyses: List<AnalysisResult>,
    marketStats: MarketStats
) {
    val verified = analyses.filter { it.status != SignalStatus.PENDING }
    val hits = verified.count { it.status == SignalStatus.HIT }
    val hitRate = if (verified.isNotEmpty()) hits.toDouble() / verified.size * 100.0 else 0.0

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SlateDark)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SlateSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Genel İstatistik", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatCell("Likidasyon", marketStats.totalLiquidations.toString(), TextPrimary)
                        StatCell("Short", marketStats.shortLiquidations.toString(), NeonRed)
                        StatCell("Long", marketStats.longLiquidations.toString(), NeonGreen)
                        StatCell("Analiz", marketStats.totalAnalyses.toString(), ElectricCyan)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatCell("Doğrulanan", marketStats.verifiedAnalyses.toString(), TextPrimary)
                        StatCell("HİT", marketStats.hitCount.toString(), NeonGreen)
                        StatCell("MİSS", marketStats.missCount.toString(), NeonRed)
                        StatCell("İsabet", String.format("%.0f%%", hitRate), if (hitRate >= 50.0) NeonGreen else NeonRed)
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    // Hit-rate visual bar
                    val total = maxOf(1, verified.size)
                    val hitRatio = (hits.toFloat() / total).coerceIn(0.02f, 0.98f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(SlateBorder)
                    ) {
                        Box(modifier = Modifier.weight(hitRatio).fillMaxSize().background(NeonGreen))
                        Box(modifier = Modifier.weight(1f - hitRatio).fillMaxSize().background(NeonRed))
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SlateCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = "Liderlik",
                            tint = NeonAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "En Çok Kısa Tasfiye Olan Coinler",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    if (marketStats.topLiquidatedSymbols.isEmpty()) {
                        Text("Veri biriktikçe lider tablosu güncellenir.", fontSize = 12.sp, color = TextMuted)
                    } else {
                        marketStats.topLiquidatedSymbols.forEachIndexed { index, (symbol, vol) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = when (index) {
                                            0 -> NeonAmber
                                            1 -> TextSecondary
                                            2 -> ElectricCyan
                                            else -> SlateSurface
                                        }
                                    ) {
                                        Text(
                                            text = "#${index + 1}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (index < 3) SlateDark else TextPrimary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(symbol, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                }
                                Text(
                                    text = "$${String.format("%,.0f", vol)}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonRed
                                )
                            }
                            if (index < marketStats.topLiquidatedSymbols.size - 1) {
                                Divider(color = SlateBorder, thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SlateCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Çok Faktörlü Model Ağırlıkları", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(modifier = Modifier.height(12.dp))
                    FactorRow("📈 Emir Defteri (derinlik/wall/spoof)", "%15", ElectricCyan)
                    FactorRow("💵 Trade Akışı (CVD, taker)", "%15", ElectricCyan)
                    FactorRow("📊 Açık Pozisyon (OI değişimi)", "%12", ElectricCyan)
                    FactorRow("💥 Likidasyon (long/short dengesi)", "%12", ElectricCyan)
                    FactorRow("🧠 Momentum (RSI/EMA/MACD/ATR)", "%10", ElectricCyan)
                    FactorRow("🎯 Opsiyonlar (DVOL)", "%10", ElectricCyan)
                    FactorRow("🐋 Balina Net Akışı", "%10", ElectricCyan)
                    FactorRow("💰 Funding (squeeze tespiti)", "%8", ElectricCyan)
                    FactorRow("📦 Hacim (volüm rejimi)", "%8", ElectricCyan)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Dinamik ağırlık kaydırma: düşük likiditede Emir Defteri + Trade Akışı güçlendirilir; " +
                            "yüksek volatilitede Hacim + Momentum güçlendirilir. Veri olmayan bileşenler ağırlık " +
                            "yeniden normalizasyonu ile hariç tutulur.",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
            }
        }

        // Calibration / component correlations table
        val correlations = analyses
            .firstOrNull { it.calibration.componentCorrelations.isNotEmpty() }
            ?.calibration
            ?.componentCorrelations
        if (correlations != null && correlations.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SlateCard)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Bileşen-Fiyat Korelasyonları (Pearson)",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        correlations.entries.sortedByDescending { it.value }.forEach { (key, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(key, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                                Text(
                                    text = String.format("%+.3f", value),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        value > 0.15 -> NeonGreen
                                        value < -0.15 -> NeonRed
                                        else -> TextPrimary
                                    }
                                )
                            }
                            Divider(color = SlateBorder, thickness = 0.5.dp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Hangi bileşenin bu sembol için gerçekten tahmin gücü olduğunu gösterir.",
                            fontSize = 10.sp,
                            color = TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(label, fontSize = 10.sp, color = TextMuted)
    }
}

@Composable
private fun FactorRow(title: String, weight: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Text(weight, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
    }
    Divider(color = SlateBorder, thickness = 0.5.dp)
}
