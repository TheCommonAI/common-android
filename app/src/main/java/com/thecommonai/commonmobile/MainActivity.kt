package com.thecommonai.commonmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("common", MODE_PRIVATE)
        setContent {
            CommonApp(
                initialGateway = preferences.getString("gateway", NetworkClient.DEFAULT_GATEWAY)
                    ?: NetworkClient.DEFAULT_GATEWAY,
                onGatewaySaved = { preferences.edit().putString("gateway", it).apply() },
            )
        }
    }
}
