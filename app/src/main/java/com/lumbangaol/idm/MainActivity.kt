package com.lumbangaol.idm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {

    /** Teks yang dibagikan dari aplikasi lain (misalnya link dari browser). */
    private val sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val engine = (application as IdmApp).engine
        setContent {
            IdmTheme {
                MainScreen(
                    engine = engine,
                    sharedText = sharedText.value,
                    onSharedConsumed = { sharedText.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) sharedText.value = text
        }
    }
}
