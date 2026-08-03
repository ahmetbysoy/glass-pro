package com.glasspro.tracker.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glasspro.tracker.core.model.SignalStatus
import com.glasspro.tracker.ui.theme.ElectricCyan
import com.glasspro.tracker.ui.theme.ElectricCyanBg
import com.glasspro.tracker.ui.theme.NeonRed
import com.glasspro.tracker.ui.theme.NeonRedBg
import com.glasspro.tracker.ui.theme.NeonGreen
import com.glasspro.tracker.ui.theme.SlateDark
import com.glasspro.tracker.ui.theme.SlateSurface
import com.glasspro.tracker.ui.theme.TextPrimary
import com.glasspro.tracker.ui.theme.TextSecondary
import com.glasspro.tracker.ui.viewmodel.MarketViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MarketViewModel) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val liquidations by viewModel.liquidations.collectAsStateWithLifecycle()
    val analyses by viewModel.analyses.collectAsStateWithLifecycle()
    val marketStats by viewModel.marketStats.collectAsStateWithLifecycle()
    val feedHealth by viewModel.feedHealth.collectAsStateWithLifecycle()
    val minThreshold by viewModel.minThreshold.collectAsStateWithLifecycle()
    val excludeBtcEth by viewModel.excludeBtcEth.collectAsStateWithLifecycle()
    val isLiveStreaming by viewModel.isLiveStreaming.collectAsStateWithLifecycle()
    val cooldownSec by viewModel.cooldownSec.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()

    val verified = analyses.filter { it.status != SignalStatus.PENDING }
    val hitCount = verified.count { it.status == SignalStatus.HIT }
    val hitRate = if (verified.isNotEmpty()) hitCount.toDouble() / verified.size * 100.0 else 0.0

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(NeonRed, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "Logo",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "GlassPro",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Gerçek Likidasyon & Analiz",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                    }
                },
                actions = {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (hitRate >= 50.0) com.glasspro.tracker.ui.theme.NeonGreenBg else NeonRedBg
                    ) {
                        Text(
                            text = String.format("%.0f HİT", hitRate),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (hitRate >= 50.0) NeonGreen else NeonRed,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Surface(shape = RoundedCornerShape(12.dp), color = ElectricCyanBg) {
                        Text(
                            text = "≥$${(minThreshold / 1000.0).toInt()}K",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SlateSurface,
                    titleContentColor = TextPrimary
                )
            )
        },
        bottomBar = {
            Surface(
                color = SlateSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BottomBarItem(0, "Canlı Akış", Icons.Default.Bolt, selectedTab) { viewModel.setSelectedTab(0) }
                    BottomBarItem(1, "Tahminler", Icons.Default.Psychology, selectedTab) { viewModel.setSelectedTab(1) }
                    BottomBarItem(2, "İstatistik", Icons.Default.BarChart, selectedTab) { viewModel.setSelectedTab(2) }
                    BottomBarItem(3, "Ayarlar", Icons.Default.Settings, selectedTab) { viewModel.setSelectedTab(3) }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> LiveFeedTab(
                    liquidations = liquidations,
                    marketStats = marketStats,
                    feedHealth = feedHealth,
                    minThreshold = minThreshold,
                    excludeBtcEth = excludeBtcEth,
                    isLiveStreaming = isLiveStreaming,
                    onThresholdSelected = { viewModel.setMinThreshold(it) },
                    onToggleExcludeBtcEth = { viewModel.setExcludeBtcEth(it) },
                    onToggleLiveStreaming = { viewModel.setLiveStreaming(it) },
                    onTriggerManualAnalysis = { viewModel.triggerManualAnalysis(it) }
                )
                1 -> AnalysisTab(
                    analyses = analyses,
                    onTriggerManualAnalysis = { viewModel.triggerManualAnalysis(it) }
                )
                2 -> AnalyticsTab(
                    analyses = analyses,
                    marketStats = marketStats
                )
                3 -> SettingsTab(
                    minThreshold = minThreshold,
                    excludeBtcEth = excludeBtcEth,
                    isLiveStreaming = isLiveStreaming,
                    cooldownSec = cooldownSec,
                    feedHealth = feedHealth,
                    onThresholdChange = { viewModel.setMinThreshold(it) },
                    onToggleExcludeBtcEth = { viewModel.setExcludeBtcEth(it) },
                    onToggleLiveStreaming = { viewModel.setLiveStreaming(it) },
                    onCooldownChange = { viewModel.setCooldownSeconds(it) },
                    onClearHistory = { viewModel.clearHistory() }
                )
            }

            banner?.let { (event, dirSym) ->
                AnimatedVisibility(
                    visible = true,
                    enter = slideInVertically(initialOffsetY = { -it }),
                    exit = slideOutVertically(targetOffsetY = { -it }),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = NeonRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.dismissBanner()
                                viewModel.setSelectedTab(1)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🔴 KISA TASFİYE TESPİT EDİLDİ!",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "${event.symbol} ($${String.format("%,.0f", event.notionalUsd)}) " +
                                        "-> 1DK TAHMİN: $dirSym",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                            }
                            IconButton(onClick = { viewModel.dismissBanner() }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Kapat",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Custom bottom navigation item rendered without NavigationBarItem. A plain
 * clickable row with an icon and label; the selected state is highlighted in
 * the accent color. Keeping this dependency-light avoids any Material3 API
 * surface that could be unresolved under the AGP 9 built-in Kotlin toolchain.
 */
@Composable
private fun BottomBarItem(
    index: Int,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selectedTab: Int,
    onClick: () -> Unit
) {
    val selected = selectedTab == index
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) ElectricCyan else com.glasspro.tracker.ui.theme.TextMuted,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) ElectricCyan else com.glasspro.tracker.ui.theme.TextMuted
        )
    }
}
