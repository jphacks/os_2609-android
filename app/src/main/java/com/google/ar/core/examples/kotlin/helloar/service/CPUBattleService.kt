package com.google.ar.core.examples.kotlin.helloar.service

import com.google.ar.core.examples.kotlin.helloar.logic.BattleEngine
import com.google.ar.core.examples.kotlin.helloar.model.battle.BattleAction
import com.google.ar.core.examples.kotlin.helloar.model.battle.BattleMonster
import com.google.ar.core.examples.kotlin.helloar.model.battle.Fighter
import com.google.ar.core.examples.kotlin.helloar.viewmodel.BattleViewModel
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * ⚠️ 開発用：CPU が相手になる対戦（iOS の CPUBattleService と同じ動き）
 * Supabase の鍵がない（local.properties に書いていない）メンバーでも、バトルの画面を動かせるようにする
 * 削除するときは、BattleActivity の BattleServices.shared で CPUBattleService を選んでいるところも一緒に消す
 *
 * 相手（CPU）は一定の間隔で通常攻撃してくる。必殺技ゲージが溜まったら必殺技を撃つ
 * こちらの必殺技は、ときどきよける
 */
class CPUBattleService : BattleService {
  private val scope: CoroutineScope = MainScope()
  /** CPU の行動を流す先。こちらの必殺技に、受けたかよけたかを返すのに使う */
  private var actions: ProducerScope<BattleAction>? = null

  override suspend fun findOpponent(monster: BattleMonster): BattleMonster {
    delay(1_500)
    return BattleMonster.ALL.random()
  }

  override fun opponentActions(): Flow<BattleAction> = callbackFlow {
    actions = this
    val job = launch {
      var attackCount = 0
      while (true) {
        delay((ATTACK_INTERVAL * 1000).toLong())
        if (attackCount == ATTACKS_BEFORE_SPECIAL) {
          attackCount = 0
          send(BattleAction.SPECIAL_ATTACK)
        } else {
          attackCount += 1
          send(BattleAction.NORMAL_ATTACK)
        }
      }
    }
    // 受け取る側が止まったら（決着した、対戦をやめた）攻撃も止める
    awaitClose {
      job.cancel()
      actions = null
    }
  }.buffer(Channel.UNLIMITED)

  override fun send(action: BattleAction) {
    // CPU が気にするのは必殺技だけ。本物の相手と同じく、予告の時間がたってから結果を返す
    if (action != BattleAction.SPECIAL_ATTACK) return
    scope.launch(Dispatchers.Main) {
      delay((BattleViewModel.DANGER_DURATION * 1000).toLong())
      val result = if (Random.nextDouble() < DODGE_CHANCE) BattleAction.DODGE_SPECIAL else BattleAction.TAKE_SPECIAL
      actions?.trySend(result)
    }
  }

  override suspend fun finish(iWon: Boolean): Boolean = iWon

  override suspend fun leave() {}

  private companion object {
    /** CPU が攻撃する間隔（秒） */
    const val ATTACK_INTERVAL = 1.0
    /** CPU がこちらの必殺技をよける確率 */
    const val DODGE_CHANCE = 0.3
    /** 必殺技ゲージが満タンになるまでの通常攻撃の回数 */
    const val ATTACKS_BEFORE_SPECIAL = Fighter.SPECIAL_GAUGE_MAX / BattleEngine.SPECIAL_GAUGE_GAIN
  }
}
