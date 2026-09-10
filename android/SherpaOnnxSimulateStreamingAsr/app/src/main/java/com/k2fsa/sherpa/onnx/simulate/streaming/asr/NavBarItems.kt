package com.k2fsa.sherpa.onnx.simulate.streaming.asr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info

object NavBarItems {
    val BarItems = listOf(
        BarItem(
            title = "背诵",
            image = Icons.Filled.Home,
            route = "home",
        ),
        BarItem(
            title = "帮助",
            image = Icons.Filled.Info,
            route = "help",
        ),
    )
}