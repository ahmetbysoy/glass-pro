package com.glasspro.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.glasspro.tracker.ui.screens.MainScreen
import com.glasspro.tracker.ui.theme.GlassProTheme
import com.glasspro.tracker.ui.viewmodel.MarketViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MarketViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GlassProTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onDestroy() {
        // Repository workers are tied to the application scope; nothing to
        // tear down here, but activity must not leak viewModel bindings.
        super.onDestroy()
    }
}
