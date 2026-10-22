package com.thecommonai.commonmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    private lateinit var contributor: AndroidContributorController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contributor = AndroidContributorController(applicationContext)
        val preferences = getSharedPreferences("common", MODE_PRIVATE)
        setContent {
            CommonApp(
                initialGateway = preferences.getString("gateway", NetworkClient.DEFAULT_GATEWAY)
                    ?: NetworkClient.DEFAULT_GATEWAY,
                onGatewaySaved = { preferences.edit().putString("gateway", it).apply() },
                contributorController = contributor,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        contributor.onAppForeground()
    }

    override fun onStop() {
        contributor.onAppBackground()
        super.onStop()
    }

    override fun onDestroy() {
        contributor.close()
        super.onDestroy()
    }
}
