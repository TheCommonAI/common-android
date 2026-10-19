package com.thecommonai.commonmobile

import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    val preferences = NSUserDefaults.standardUserDefaults
    CommonApp(
        initialGateway = preferences.stringForKey("gateway") ?: NetworkClient.DEFAULT_GATEWAY,
        onGatewaySaved = { preferences.setObject(it, forKey = "gateway") },
    )
}
