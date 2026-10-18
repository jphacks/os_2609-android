package com.google.ar.core.examples.kotlin.helloar.battle

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.google.ar.core.examples.kotlin.helloar.battle.ar.ArHandoff
import com.google.ar.core.examples.kotlin.helloar.model.Monster
import com.google.ar.core.examples.kotlin.helloar.viewmodel.BattleViewModel

/**
 * 対戦画面（iOS の BattleView）。「バトルする」で対戦相手を探し、見つかったら対戦が始まる
 * 画面の空いている所をタップして攻撃し、左右にスワイプしてよける（必殺技とフィーバーは自動）
 * 対戦中は、カメラに映る床の上でモンスター同士が戦う（AR）
 * 対戦前は BattleLobbyComponent、対戦中は BattleFightComponent を出す
 * マルチバトル（BattleActivity）ではほかのプレイヤーと、捕獲画面では野生のモンスター（CPU）と戦う
 *
 * @param wildBattle 野生のモンスターとのバトルなら、その相手と終わったときに呼ぶ処理。ほかのプレイヤーとの対戦なら null
 */
@Composable
fun BattleScreen(viewModel: BattleViewModel, wildBattle: WildBattle? = null) {
  // アプリが隠れたとき（ホームに戻った、画面を消した）は、相手を待つのをやめて部屋を片付ける（対戦中ならそのまま続ける）
  // 待ったままにすると、隠れても部屋がしばらく残り、ほかの人がいない相手とつながってしまう
  // 野生のモンスターとのバトルの対戦中なら、モンスターに逃げられたことにして捕獲画面に戻る（決着していたら、その結果で戻る）
  // 相手の CPU はこの端末の中で動いていて、アプリが隠れている間も攻撃し続けるので、戻ってきたときには負けてしまっている
  // 対戦前は CPU が攻撃してこないので、そのまま待つ
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
    viewModel.cancelSearch()
    if (wildBattle != null && viewModel.me != null) {
      viewModel.outcome?.let(wildBattle.onFinish) ?: wildBattle.onFlee()
    }
  }

  val me = viewModel.me
  val opponent = viewModel.opponent
  val opponentMonster = viewModel.opponentMonster
  val isFighting = me != null && opponent != null && opponentMonster != null
  // 野生のモンスターとのバトルでは、戻るボタンで捕獲画面を閉じない（マップ画面まで戻ってしまうため）
  // 対戦前は「にげる」と同じにする。対戦中は何もしない（iOS も対戦中は抜けられない。決着すると捕獲画面に戻る）
  if (wildBattle != null) {
    BackHandler {
      if (!isFighting) wildBattle.onFlee()
    }
  }

  if (me != null && opponent != null && opponentMonster != null) {
    BattleFightComponent(
      viewModel, me, opponent, viewModel.myMonster, opponentMonster,
      arHandoff = wildBattle?.arHandoff,
      onFinish = wildBattle?.onFinish,
    )
  } else {
    BattleLobbyComponent(
      myMonster = viewModel.myMonster,
      isSearching = viewModel.phase == BattleViewModel.Phase.SEARCHING ||
        viewModel.phase == BattleViewModel.Phase.CANCELLING_SEARCH,
      isCancelling = viewModel.phase == BattleViewModel.Phase.CANCELLING_SEARCH,
      errorMessage = viewModel.errorMessage,
      onFind = if (wildBattle != null) ({ viewModel.findOpponent() }) else null,
      onCancel = viewModel::cancelSearch,
      wildMonster = wildBattle?.monster,
      onFlee = wildBattle?.onFlee,
    )
  }
}

/** 野生のモンスターとのバトル（捕獲画面から開いたとき）の相手と、終わったときに呼ぶ処理（iOS の BattleView.WildBattle） */
class WildBattle(
  /** 相手の野生のモンスター。対戦前の画面に、自分のモンスターと並べて出す */
  val monster: Monster,
  /** 捕獲画面から引き継ぐ AR。対戦中は、捕獲画面と同じ AR の中で、野生のモンスターがいた場所に相手を置く */
  val arHandoff: ArHandoff,
  /**
   * 決着して、勝ち負けの演出を見せ終わったときに呼ぶ。勝敗の結果の画面は出さない
   * （勝てば捕獲画面が捕獲成功の画面を出し、負ければ「捕獲失敗…」を見せ終わってから呼ぶ）
   */
  val onFinish: (BattleViewModel.Outcome) -> Unit,
  /** 対戦前に「にげる」（または戻るボタン）が押されたときと、対戦中にアプリが隠れたときに呼ぶ */
  val onFlee: () -> Unit,
)
