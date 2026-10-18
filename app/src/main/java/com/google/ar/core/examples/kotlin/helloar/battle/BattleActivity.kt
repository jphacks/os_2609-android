package com.google.ar.core.examples.kotlin.helloar.battle

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import java.util.UUID
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.ar.core.examples.kotlin.helloar.service.BattleService
import com.google.ar.core.examples.kotlin.helloar.service.SupabaseBattleService
import com.google.ar.core.examples.kotlin.helloar.service.SupabaseConfig
import com.google.ar.core.examples.kotlin.helloar.viewmodel.BattleViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/** マルチバトルの画面。マップ画面で近接相手との双方承諾後に開く */
class BattleActivity : ComponentActivity() {
  companion object { const val PAIRING_TOKEN = "pairing_token" }

  private val battleViewModel: BattleViewModel by viewModels {
    viewModelFactory { initializer { BattleViewModel(BattleServices.shared, BattleServices.scope) } }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // 暗い画面なので、上の時計や電池の表示を白くする
    enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
    val token = intent.getStringExtra(PAIRING_TOKEN)?.let {
      runCatching { UUID.fromString(it).toString() }.getOrNull()
    }
    if (token == null || !SupabaseConfig.isConfigured) {
      Toast.makeText(this, "マップ画面で対戦受付をオンにしてください", Toast.LENGTH_LONG).show()
      finish()
      return
    }
    // 回転で Activity が作り直されても、同じ部屋へ二重参加しない。
    if (savedInstanceState == null) battleViewModel.findOpponent(token)
    setContent { BattleScreen(battleViewModel) }
  }
}

/**
 * 対戦の通信は、画面を開き直しても作り直さないよう、アプリ全体で 1 つだけ使う（iOS も ContentView で 1 つだけ作る）
 * scope は、画面を閉じたあとに対戦から抜ける通信を、最後まで行うのに使う
 */
private object BattleServices {
  // ここは近接プレイヤー対戦専用。野生対戦は CaptureScreen が CPU を作る。
  val shared: BattleService by lazy {
    SupabaseBattleService(SupabaseConfig.url, SupabaseConfig.anonKey)
  }
  val scope: CoroutineScope = MainScope()
}
