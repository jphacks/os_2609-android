package com.google.ar.core.examples.kotlin.helloar.battle

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.ar.core.examples.kotlin.helloar.service.BattleService
import com.google.ar.core.examples.kotlin.helloar.service.CPUBattleService
import com.google.ar.core.examples.kotlin.helloar.service.SupabaseBattleService
import com.google.ar.core.examples.kotlin.helloar.service.SupabaseConfig
import com.google.ar.core.examples.kotlin.helloar.viewmodel.BattleViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/** マルチバトルの画面。マップ画面（MapActivity）の「マルチバトル」ボタンから開く */
class BattleActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // 暗い画面なので、上の時計や電池の表示を白くする
    enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
    setContent {
      val viewModel: BattleViewModel = viewModel(
        factory = viewModelFactory { initializer { BattleViewModel(BattleServices.shared, BattleServices.scope) } },
      )
      BattleScreen(viewModel)
    }
  }
}

/**
 * 対戦の通信は、画面を開き直しても作り直さないよう、アプリ全体で 1 つだけ使う（iOS も ContentView で 1 つだけ作る）
 * scope は、画面を閉じたあとに対戦から抜ける通信を、最後まで行うのに使う
 */
private object BattleServices {
  /**
   * local.properties に Supabase の接続先が書いてあれば Supabase で対戦し、なければ CPU が相手になる
   * ⚠️ 開発用：ここで CPUBattleService を使うのは開発中だけ。リリースするときは SupabaseBattleService だけにする
   * （CPUBattleService 自体は、捕獲画面の野生のモンスターとのバトルで使うので消さない）
   */
  val shared: BattleService by lazy {
    if (SupabaseConfig.isConfigured) {
      SupabaseBattleService(SupabaseConfig.url, SupabaseConfig.anonKey)
    } else {
      CPUBattleService()
    }
  }
  val scope: CoroutineScope = MainScope()
}
