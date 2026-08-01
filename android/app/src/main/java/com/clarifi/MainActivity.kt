package com.clarifi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.clarifi.ui.nav.ClariFiRoot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Drawn behind the system bars; ClariFiTheme keeps the bar icons legible
        // and each screen consumes the insets it needs.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            ClariFiRoot(container = (application as ClariFiApp).container)
        }
    }
}
