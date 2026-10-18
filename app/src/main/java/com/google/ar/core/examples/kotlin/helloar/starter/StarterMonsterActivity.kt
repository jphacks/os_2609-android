package com.google.ar.core.examples.kotlin.helloar.starter

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ar.core.examples.kotlin.helloar.ARAppApplication
import com.google.ar.core.examples.kotlin.helloar.capture.CaptureActivity
import com.google.ar.core.examples.kotlin.helloar.map.MapActivity
import com.google.ar.core.examples.kotlin.helloar.viewmodel.StarterMonsterViewModel

/**
 * 最初にモンスターを選ぶ画面。バトルに出すモンスターがまだ決まっていないとき、マップ画面（MapActivity）の代わりに開く
 * 決めたら捕獲画面へ進む（iOS と同じ）。捕獲画面から戻るとマップ画面になるよう、マップ画面を下に積んでおく
 * 決める前に戻るボタンを押すと、何も保存せずにアプリを閉じる。次に開いたときも、この画面から始まる
 * （iOS もこの画面を閉じる方法はなく、決めないと先に進めない）
 */
class StarterMonsterActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // 白い画面なので、上の時計や電池の表示を暗い色にする
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
    )
    val collection = (application as ARAppApplication).monsterCollection
    setContent {
      val viewModel: StarterMonsterViewModel = viewModel(factory = StarterMonsterViewModel.factory(collection))
      StarterMonsterScreen(viewModel, onDecided = ::openCapture)
    }
  }

  private fun openCapture() {
    // ボタンを続けて押したときに、捕獲画面が 2 つ開かないようにする
    if (isFinishing) return
    startActivities(
      arrayOf(
        Intent(this, MapActivity::class.java),
        Intent(this, CaptureActivity::class.java),
      ),
    )
    finish()
  }
}
