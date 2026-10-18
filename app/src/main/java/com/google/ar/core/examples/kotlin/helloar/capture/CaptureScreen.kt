package com.google.ar.core.examples.kotlin.helloar.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ar.core.examples.kotlin.helloar.battle.BattleHaptic
import com.google.ar.core.examples.kotlin.helloar.battle.BattleStyle
import com.google.ar.core.examples.kotlin.helloar.battle.battlePanel
import com.google.ar.core.examples.kotlin.helloar.battle.perform
import com.google.ar.core.examples.kotlin.helloar.capture.ar.CaptureArComponent
import com.google.ar.core.examples.kotlin.helloar.model.Monster
import com.google.ar.core.examples.kotlin.helloar.viewmodel.CaptureViewModel
import kotlinx.coroutines.delay

/**
 * AR 捕獲画面（iOS の ARCaptureView）。歩き回るとモンスターが出て、AR で周りを歩く
 * タップすると捕まえて、捕獲成功の画面（MonsterDetailComponent）に切り替わる。「つぎへ」で AR に戻る
 *
 * AR（ARSceneView）と捕獲成功の画面の 3D（MonsterModelComponent）は、どちらも Filament で描く別々の画面なので、同時に出さない
 * 重ねると、後ろの AR の画面が前に出てしまったり、カメラを取り合ったりすることがある
 * そのため前の画面を外してから少し待って、次の画面を出す（iOS の ARCaptureView の arScreen と同じ）
 */
@Composable
fun CaptureScreen(viewModel: CaptureViewModel) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  /** 今出している捕獲成功の画面のモンスター。null なら AR を出す */
  var shownDetail by remember { mutableStateOf<Monster?>(null) }
  /** 前の画面を外して、次の画面を出すのを待っている */
  var isSwitching by remember { mutableStateOf(false) }
  LaunchedEffect(state.detailMonster) {
    if (state.detailMonster == shownDetail) return@LaunchedEffect
    isSwitching = true
    delay(SWITCH_DELAY_MS)
    shownDetail = state.detailMonster
    isSwitching = false
  }

  // モンスターが現れたら振動させ、画面を見ていなくても気づけるようにする
  val view = LocalView.current
  LaunchedEffect(state.wildMonster?.id) {
    if (state.wildMonster != null) view.perform(BattleHaptic.WARNING)
  }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    val detail = shownDetail
    when {
      isSwitching -> Unit
      detail != null -> MonsterDetailComponent(detail, onClose = viewModel::closeDetail)
      else -> {
        CaptureArComponent(viewModel, Modifier.fillMaxSize())
        Text(
          state.statusMessage,
          style = BattleStyle.display(18.sp),
          color = Color.White,
          textAlign = TextAlign.Center,
          modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(16.dp)
            .battlePanel(BattleStyle.cyan),
        )
      }
    }
  }
}

/** 前の画面を外してから、次の画面を出すまでの時間（ミリ秒）。AR のカメラやセッションが止まりきるのを待つ */
private const val SWITCH_DELAY_MS = 300L
