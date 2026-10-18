package com.google.ar.core.examples.kotlin.helloar.capture

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ar.core.examples.kotlin.helloar.ARAppApplication
import com.google.ar.core.examples.kotlin.helloar.viewmodel.CaptureViewModel

/** AR 捕獲画面。マップ画面（MapActivity）の「AR で捕獲する」ボタンから開き、戻るボタンでマップ画面に戻る */
class CaptureActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // カメラの映像や暗い画面の上に出すので、上の時計や電池の表示を白くする
    enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
    setContent {
      val viewModel: CaptureViewModel = viewModel(
        factory = CaptureViewModel.factory((application as ARAppApplication).monsterCollection),
      )
      CaptureScreen(viewModel)
    }
  }
}
