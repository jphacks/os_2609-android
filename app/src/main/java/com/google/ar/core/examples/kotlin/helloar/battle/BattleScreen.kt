package com.google.ar.core.examples.kotlin.helloar.battle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.google.ar.core.examples.kotlin.helloar.viewmodel.BattleViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 対戦画面（iOS の BattleView）。「バトルする」で対戦相手を探し、見つかったら対戦が始まる
 * 画面の空いている所をタップして攻撃し、左右にスワイプしてよける（必殺技とフィーバーは自動）
 * 対戦中は、カメラに映る床の上でモンスター同士が戦う（AR）
 * 対戦前は BattleLobbyComponent、対戦中は BattleFightComponent を出す
 */
@Composable
fun BattleScreen(viewModel: BattleViewModel) {
  // アプリが隠れたとき（ホームに戻った、画面を消した）は、相手を待つのをやめて部屋を片付ける（対戦中ならそのまま続ける）
  // 待ったままにすると、隠れても部屋がしばらく残り、ほかの人がいない相手とつながってしまう
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
    viewModel.cancelSearch()
  }
  // 効果音は作るのに少し時間がかかるので、対戦が始まる前に裏で作っておく
  LaunchedEffect(Unit) {
    withContext(Dispatchers.Default) { BattleSoundPlayer.prepare() }
  }

  val me = viewModel.me
  val opponent = viewModel.opponent
  val opponentMonster = viewModel.opponentMonster
  if (me != null && opponent != null && opponentMonster != null) {
    BattleFightComponent(viewModel, me, opponent, viewModel.myMonster, opponentMonster)
  } else {
    BattleLobbyComponent(
      myMonster = viewModel.myMonster,
      isSearching = viewModel.phase == BattleViewModel.Phase.SEARCHING ||
        viewModel.phase == BattleViewModel.Phase.CANCELLING_SEARCH,
      isCancelling = viewModel.phase == BattleViewModel.Phase.CANCELLING_SEARCH,
      errorMessage = viewModel.errorMessage,
      onFind = viewModel::findOpponent,
      onCancel = viewModel::cancelSearch,
    )
  }
}
