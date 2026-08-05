package com.codenames.keycards

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.codenames.keycards.theme.CodenamesKeycardsTheme
import com.codenames.keycards.ui.KeycardApp

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      CodenamesKeycardsTheme { KeycardApp() }
    }
  }
}
