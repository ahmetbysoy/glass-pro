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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasspro.tracker.core.model.AnalysisResult
import com.glasspro.tracker.core.model.Direction
import com.glasspro.tracker.core.model.SignalStatus
import com.glasspro.tracker.ui.theme.ElectricCyan
import com.glasspro.tracker.ui.theme.ElectricCyanBg
import com.glasspro.tracker.ui.theme.NeonAmber
import com.glasspro.tracker.ui.theme.NeonAmberBg
import com.glasspro.tracker.ui.theme.NeonGreen
import com.glasspro.tracker.ui.theme.NeonGreenBg
import com.glasspro.tracker.ui.theme.NeonRed
import com.glasspro.tracker.ui.theme.NeonRedBg
import com.glasspro.tracker.ui.theme.PurpleAccent
import com.glasspro.tracker.ui.theme.PurpleAccentBg
import com.glasspro.tracker.ui.theme.SlateBorder
import com.glasspro.tracker.ui.theme.SlateCard
import com.glasspro.tracker.ui.theme.SlateDark
import com.glasspro.tracker.ui.theme.SlateSurface
import com.glasspro.tracker.ui.theme.TextMuted
import com.glasspro.tracker.ui.theme.TextPrimary
import com.glasspro.tracker.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AnalysisTab(
    analyses: List<AnalysisResult>,
    onTriggerManualAnalysis: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlateDark)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Hero metrics
        val verified = analyses.filter { it.status != SignalStatus.PENDING }
        val hits = verified.count { it.status == SignalStatus.HIT }
        val hitRate = if (verified.isNotEmpty()) hits.toDouble() / verified.size * 100.0 else 0.0

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SlateSurface)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricBlock("Toplam Analiz", analyses.size.toString(), TextPrimary)
                MetricBlock("60sn/1S İsabet", String.format("%.1f%%", hitRate), if (hitRate >= 50.0) NeonGreen else NeonRed)
                Column(horizontalAlignment = Alignment.End) {
                    Text("Sonuçlar", fontSize = 11.sp, color = TextSecondary)
                    Text(
                        text = "✔$hits ✘${verified.size - hits} ⌛${analyses.count { it.status == SignalStatus.PENDING }}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Profesyonel Nicel Analizler (${analyses.size})",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (analyses.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = "Analiz yok",
                        tint = TextMuted,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Eşiği geçen gerçek bir kısa tasfiye olmadı.",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Canlı tasfiye akışından eşik üstü olay gelince otomatik analiz üretilir.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)
            ) {
                items(analyses, key = { it.id }) { item ->
                    AnalysisCard(analysis = item)
                }
            }
        }
    }
}

@Composable
private fun MetricBlock(label: String, value: String, color: Color) {
    Column {
        Text(label, fontSize = 11.sp, color = TextSecondary)
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
fun AnalysisCard(analysis: AnalysisResult) {
    var remainingSeconds by remember { mutableLongStateOf(0L) }
    val isPending = analysis.status == SignalStatus.PENDING

    LaunchedEffect(analysis.verifyAtMs, analysis.status) {
        if (isPending) {
            while (true) {
                val rem = maxOf(0L, (analysis.verifyAtMs - System.currentTimeMillis()) / 1000L)
                remainingSeconds = rem
                if (rem <= 0L) break
                delay(1000L)
            }
        }
    }

    val dirColor = when (analysis.direction) {
        Direction.LONG -> NeonGreen
        Direction.SHORT -> NeonRed
        Direction.NEUTRAL -> NeonAmber
    }
    val dirBg = when (analysis.direction) {
        Direction.LONG -> NeonGreenBg
        Direction.SHORT -> NeonRedBg
        Direction.NEUTRAL -> NeonAmberBg
    }
    val statusColor = when (analysis.status) {
        SignalStatus.HIT -> NeonGreen
        SignalStatus.MISS -> NeonRed
        SignalStatus.PENDING -> SlateBorder
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = analysis.symbol,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(4.dp), color = SlateSurface) {
                        Text(
                            text = analysis.horizonLabel,
                            fontSize = 9.sp,
                            color = TextSecondary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = dirBg,
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${analysis.direction.label} ${analysis.direction.symbol}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = dirColor
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "%${analysis.confidence.toInt()}",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Fiyat: $${String.format("%.6g", analysis.price)}",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                when (analysis.status) {
                    SignalStatus.PENDING -> Text(
                        text = "Doğrulama: ${remainingSeconds}sn",
                        fontSize = 12.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                    else -> Text(
                        text = "${analysis.status.label} (${String.format("%+.2f%%", analysis.priceChangePct ?: 0.0)})",
                        fontSize = 12.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = SlateBorder, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(10.dp))

            // Component bars
            Text(
                text = "Bileşen Skorları (ağırlıklı toplam: ${String.format("%+.1f", analysis.totalScore)})",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            analysis.components.forEach { component ->
                if (component.available) {
                    ComponentBar(
                        key = component.key,
                        score = component.score,
                        weight = component.weight
                    )
                }
            }
            if (analysis.components.none { it.available }) {
                Text("Bileşen verisi yok", fontSize = 11.sp, color = TextMuted)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Probabilities
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProbCell("YUKARI", analysis.probabilities.up, NeonGreen)
                ProbCell("AŞAĞI", analysis.probabilities.down, NeonRed)
                ProbCell("BELİRSİZ", analysis.probabilities.uncertain, NeonAmber)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Strategy
            val strategy = analysis.strategy
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = PurpleAccentBg,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "Strateji: ${strategy.side.label} | Kaldıraç ${strategy.leverage}x",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = PurpleAccent
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Giriş $${String.format("%.6g", strategy.entry)} • " +
                            "SL ${strategy.stopLoss?.let { "$" + String.format("%.6g", it) } ?: "-"} • " +
                            "TP ${strategy.takeProfit?.let { "$" + String.format("%.6g", it) } ?: "-"}",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    strategy.alerts.forEach { alert ->
                        Text(
                            text = "⚠️ $alert",
                            fontSize = 10.sp,
                            color = NeonAmber
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Risk + Whale + Calibration
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat("Risk", "${analysis.risks.generalRisk.toInt()}", TextPrimary)
                MiniStat("Manip.", "${analysis.risks.manipulationIndex.toInt()}", NeonAmber)
                MiniStat("Spoof", "${analysis.risks.spoofRate.toInt()}%", NeonRed)
                MiniStat("Liq-Hunt", "${analysis.risks.liquidationHunt.toInt()}", ElectricCyan)
                MiniStat("Kaynak", "${analysis.providerCount}", NeonGreen)
            }

            if (analysis.whaleTrades.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Balina İşlemleri:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                analysis.whaleTrades.take(3).forEach { whale ->
                    Text(
                        text = "${whale.exchange} • ${if (whale.side.name == "LONG") "ALIŞ" else "SATIŞ"} " +
                            "$${String.format("%,.0f", whale.valueUsd)} @ $${String.format("%.6g", whale.price)}",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
            }

            analysis.calibration.rollingAccuracy20?.let { acc ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Kalibrasyon: ${analysis.calibration.resolvedCount} sonuç • rolling-20 isabet %${(acc * 100).toInt()}",
                    fontSize = 10.sp,
                    color = TextMuted
                )
            }

            analysis.conflicts.forEach { conflict ->
                Text(
                    text = "⚠️ $conflict",
                    fontSize = 9.sp,
                    color = NeonAmber
                )
            }

            val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            Text(
                text = dateFormat.format(Date(analysis.createdAtMs)),
                fontSize = 10.sp,
                color = TextMuted,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun ComponentBar(key: String, score: Double, weight: Double) {
    val ratio = (score.coerceIn(-100.0, 100.0) + 100.0) / 200.0
    val color = when {
        score >= 20.0 -> NeonGreen
        score <= -20.0 -> NeonRed
        else -> NeonAmber
    }
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = key,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )
            Text(
                text = "${String.format("%+.0f", score)} (%${(weight * 100).toInt()})",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(SlateBorder)
        ) {
            Box(
                modifier = Modifier
                    .weight(ratio.toFloat())
                    .fillMaxSize()
                    .background(color)
            )
            Box(
                modifier = Modifier
                    .weight((1f - ratio).toFloat())
                    .fillMaxSize()
            )
        }
    }
}

@Composable
private fun ProbCell(label: String, value: Double, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SlateSurface,
        modifier = Modifier.weight(1f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = String.format("%.0f%%", value),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(label, fontSize = 9.sp, color = TextMuted)
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SlateSurface,
        modifier = Modifier.weight(1f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(label, fontSize = 9.sp, color = TextMuted)
        }
    }
}
