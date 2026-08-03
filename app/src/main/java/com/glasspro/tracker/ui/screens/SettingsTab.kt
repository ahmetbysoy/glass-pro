package com.glasspro.tracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasspro.tracker.data.remote.adapter.AdapterHealth
import com.glasspro.tracker.data.remote.adapter.AdapterStatus
import com.glasspro.tracker.ui.theme.ElectricCyan
import com.glasspro.tracker.ui.theme.ElectricCyanBg
import com.glasspro.tracker.ui.theme.NeonAmber
import com.glasspro.tracker.ui.theme.NeonAmberBg
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

@Composable
fun SettingsTab(
    minThreshold: Double,
    excludeBtcEth: Boolean,
    isLiveStreaming: Boolean,
    cooldownSec: Long,
    feedHealth: List<AdapterHealth>,
    onThresholdChange: (Double) -> Unit,
    onToggleExcludeBtcEth: (Boolean) -> Unit,
    onToggleLiveStreaming: (Boolean) -> Unit,
    onCooldownChange: (Long) -> Unit,
    onClearHistory: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SlateDark)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SlateSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Ayarlar",
                            tint = ElectricCyan,
                            modifier = Modifier.width(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ajan & Filtre Ayarları", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Kısa Tasfiye Tetikleyici Eşik: $${String.format("%,.0f", minThreshold)}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Slider(
                        value = minThreshold.toFloat(),
                        onValueChange = { onThresholdChange(it.toDouble()) },
                        valueRange = 1000f..25000f,
                        steps = 23,
                        colors = SliderDefaults.colors(
                            thumbColor = ElectricCyan,
                            activeTrackColor = ElectricCyan,
                            inactiveTrackColor = SlateBorder
                        )
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("$1,000", fontSize = 10.sp, color = TextMuted)
                        Text("$5,000 (Varsayılan)", fontSize = 10.sp, color = ElectricCyan, fontWeight = FontWeight.Bold)
                        Text("$25,000", fontSize = 10.sp, color = TextMuted)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = SlateBorder, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    ToggleRow(
                        title = "Sadece Altcoinler",
                        subtitle = "BTC ve ETH tasfiyelerini analiz tetikleyicisinden çıkar",
                        checked = excludeBtcEth,
                        onCheckedChange = onToggleExcludeBtcEth
                    )
                    ToggleRow(
                        title = "Canlı Piyasa Akışı",
                        subtitle = "Gerçek borsa akışlarını başlat/durdur (analiz tetikleyici)",
                        checked = isLiveStreaming,
                        onCheckedChange = onToggleLiveStreaming
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Analiz Cooldown: ${cooldownSec} saniye",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Slider(
                        value = cooldownSec.toFloat(),
                        onValueChange = { onCooldownChange(it.toLong()) },
                        valueRange = 15f..600f,
                        steps = 38,
                        colors = SliderDefaults.colors(
                            thumbColor = NeonGreen,
                            activeTrackColor = NeonGreen,
                            inactiveTrackColor = SlateBorder
                        )
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("15sn", fontSize = 10.sp, color = TextMuted)
                        Text("60sn (Varsayılan)", fontSize = 10.sp, color = NeonGreen, fontWeight = FontWeight.Bold)
                        Text("10dk", fontSize = 10.sp, color = TextMuted)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = onClearHistory,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonRed),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Temizle", modifier = Modifier.width(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Veritabanı Geçmişini Temizle", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                    Text("Borsa Bağlantı Sağlığı", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(modifier = Modifier.height(12.dp))
                    feedHealth.forEach { health ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val dotColor = when (health.status) {
                                    AdapterStatus.LIVE -> NeonGreen
                                    AdapterStatus.DEGRADED -> NeonAmber
                                    AdapterStatus.DOWN -> NeonRed
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = when (health.status) {
                                        AdapterStatus.LIVE -> com.glasspro.tracker.ui.theme.NeonGreenBg
                                        AdapterStatus.DEGRADED -> NeonAmberBg
                                        AdapterStatus.DOWN -> NeonRedBg
                                    }
                                ) {
                                    Text(
                                        text = health.exchange,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = dotColor,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Text(
                                text = when (health.status) {
                                    AdapterStatus.LIVE -> "CANLI (${health.liquidationsReceived} likidasyon)"
                                    AdapterStatus.DEGRADED -> "GECİKMELİ"
                                    AdapterStatus.DOWN -> "KOPUK"
                                },
                                fontSize = 11.sp,
                                color = when (health.status) {
                                    AdapterStatus.LIVE -> NeonGreen
                                    AdapterStatus.DEGRADED -> NeonAmber
                                    AdapterStatus.DOWN -> NeonRed
                                }
                            )
                        }
                        Divider(color = SlateBorder, thickness = 0.5.dp)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Likidasyon kaynakları: Binance !forceOrder WSS, OKX liquidation-orders WSS, " +
                            "Bybit REST. Piyasa verisi: OKX, Binance, Bybit, Bitget, Hyperliquid, Gate.",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(subtitle, fontSize = 11.sp, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = SlateDark,
                checkedTrackColor = ElectricCyan,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = SlateBorder
            )
        )
    }
}
