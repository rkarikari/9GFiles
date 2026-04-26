package com.radiozport.ninegfiles.ui.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.radiozport.ninegfiles.ui.main.MainActivity
import com.radiozport.ninegfiles.utils.AppLockManager

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { false }

        if (AppLockManager.isAppLockEnabled(this)) {
            AppLockManager.authenticate(
                activity = this,
                title = "Unlock 9GFiles",
                subtitle = "Authenticate to access your files"
            ) { success, errorMsg ->
                if (success) {
                    launchMain()
                } else {
                    // User cancelled or error — stay on splash / finish
                    finish()
                }
            }
        } else {
            launchMain()
        }
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = intent.action
            data = intent.data
            type = intent.type
            if (intent.extras != null) putExtras(intent.extras!!)
        })
        finish()
    }
}
