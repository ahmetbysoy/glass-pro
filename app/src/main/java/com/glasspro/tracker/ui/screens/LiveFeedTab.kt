package com.glasspro.tracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.glasspro.tracker.core.model.LiquidationEvent
import com.glasspro.tracker.core.model.LiquidationSide
import com.glasspro.tracker.core.model.MarketStats
import com.glasspro.tracker.data.remote.adapter.AdapterHealth
import com.glasspro.tracker.data.remote.adapter.AdapterStatus
import com.glasspro.tracker.ui.theme.ElectricCyan
import com.glasspro.tracker.ui.theme.ElectricCyanBg
import com.glasspro.tracker.ui.theme.NeonGreen
import com.glasspro.tracker.ui.theme.NeonRed
import com.glasspro.tracker.ui.theme.NeonRedBg
import com.glasspro.tracker.ui.theme.SlateBorder
import com.glasspro.tracker.ui.theme.SlateCard
import com.glasspro.tracker.ui.theme.SlateDark
import com.glasspro.tracker.ui.theme.SlateSurface
import com.glasspro.tracker.ui.theme.TextMuted
import com.glasspro.tracker.ui.theme.TextPrimary
import com.glasspro.tracker.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live real liquidation feed. Every row is a real force-order event streamed
 * from Binance/OKX (or polled from Bybit). Nothing here is generated.
 */
@Composable
fun LiveFeedTab(
    liquidations: List<LiquidationEvent>,
    marketStats: MarketStats,
    feedHealth: List<AdapterHealth>,
    minThreshold: Double,
    excludeBtcEth: Boolean,
    isLiveStreaming: Boolean,
    onThresholdSelected: (Double) -> Unit,
    onToggleExcludeBtcEth: (Boolean) -> Unit,
    onToggleLiveStreaming: (Boolean) -> Unit,
    onTriggerManualAnalysis: (String) -> Unit
) {
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlateDark)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Summary card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isLiveStreaming) NeonGreen else NeonRed)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isLiveStreaming) "CANLI AKIŞ AKTİF" else "AKIŞ DURDURULDU",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isLiveStreaming) NeonGreen else NeonRed
                        )
                    }
                    IconButton(onClick = { onToggleLiveStreaming(!isLiveStreaming) }) {
                        Icon(
                            imageVector = if (isLiveStreaming) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Akışı Değiştir",
                            tint = TextPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Kısa Tasfiye (Short)", fontSize = 11.sp, color = TextSecondary)
                        Text(
                            text = "$${String.format("%,.0f", marketStats.totalShortUsd)}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonRed
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Uzun Tasfiye (Long)", fontSize = 11.sp, color = TextSecondary)
                        Text(
                            text = "$${String.format("%,.0f", marketStats.totalLongUsd)}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonGreen
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Short/long ratio bar
                val totalVol = maxOf(1.0, marketStats.totalShortUsd + marketStats.totalLongUsd)
                val shortRatio = (marketStats.totalShortUsd / totalVol).toFloat().coerceIn(0.05f, 0.95f)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(SlateBorder)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(shortRatio)
                            .fillMaxSize()
                            .background(NeonRed)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f - shortRatio)
                            .fillMaxSize()
                            .background(NeonGreen)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Exchange connection health
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            feedHealth.forEach { health ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (health.status) {
                        AdapterStatus.LIVE -> com.glasspro.tracker.ui.theme.NeonGreenBg
                        AdapterStatus.DEGRADED -> com.glasspro.tracker.ui.theme.NeonAmberBg
                        AdapterStatus.DOWN -> NeonRedBg
                    }
                ) {
                    Text(
                        text = health.exchange,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (health.status) {
                            AdapterStatus.LIVE -> NeonGreen
                            AdapterStatus.DEGRADED -> com.glasspro.tracker.ui.theme.NeonAmber
                            AdapterStatus.DOWN -> NeonRed
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Threshold chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(2500.0, 5000.0, 10000.0, 25000.0).forEach { threshold ->
                val label = when (threshold) {
                    2500.0 -> "$2.5K"
                    10000.0 -> "$10K"
                    25000.0 -> "$25K"
                    else -> "$5K"
                }
                FilterChip(
                    selected = minThreshold == threshold,
                    onClick = { onThresholdSelected(threshold) },
                    label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ElectricCyanBg,
                        selectedLabelColor = ElectricCyan,
                        containerColor = SlateCard,
                        labelColor = TextSecondary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = minThreshold == threshold,
                        borderColor = SlateBorder,
                        selectedBorderColor = ElectricCyan
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Canlı Likidasyon Akışı (${liquidations.size})",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onToggleExcludeBtcEth(!excludeBtcEth) }
                    .background(if (excludeBtcEth) ElectricCyanBg else SlateCard)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = "Filtre",
                    tint = if (excludeBtcEth) ElectricCyan else TextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (excludeBtcEth) "Sadece Altcoinler" else "BTC/ETH Dahil",
                    fontSize = 11.sp,
                    color = if (excludeBtcEth) ElectricCyan else TextMuted,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (liquidations.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = "Veri yok",
                        tint = TextMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Gerçek likidasyon verisi bekleniyor...",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)
            ) {
                items(liquidations, key = { it.id }) { item ->
                    LiquidationRow(
                        item = item,
                        minThreshold = minThreshold,
                        dateFormat = dateFormat,
                        onTriggerManualAnalysis = onTriggerManualAnalysis
                    )
                }
            }
        }
    }
}

@Composable
fun LiquidationRow(
    item: LiquidationEvent,
    minThreshold: Double,
    dateFormat: SimpleDateFormat,
    onTriggerManualAnalysis: (String) -> Unit
) {
    val isShort = item.side == LiquidationSide.SHORT
    val isHighValue = isShort && item.notionalUsd >= minThreshold

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHighValue) NeonRedBg else SlateCard
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isShort) NeonRed.copy(alpha = 0.2f) else NeonGreen.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isShort) "▼" else "▲",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isShort) NeonRed else NeonGreen
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.symbol,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = SlateSurface) {
                            Text(
                                text = item.exchange,
                                fontSize = 10.sp,
                                color = TextSecondary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                        if (isHighValue) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(4.dp), color = NeonRed) {
                                Text(
                                    text = "ALARM ≥$${(minThreshold / 1000).toInt()}K",
                                    fontSize = 9.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Fiyat: $${String.format("%.6g", item.price)} • " +
                            dateFormat.format(Date(item.timestampNs / 1_000_000L)),
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$${String.format("%,.0f", item.notionalUsd)}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isShort) NeonRed else NeonGreen
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ElectricCyanBg)
                        .clickable { onTriggerManualAnalysis(item.symbol) }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = "Analiz",
                        tint = ElectricCyan,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Analiz Et",
                        fontSize = 10.sp,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
