package com.google.ar.core.examples.kotlin.helloar.logic

import com.google.ar.core.examples.kotlin.helloar.model.battle.BattleAction
import com.google.ar.core.examples.kotlin.helloar.model.battle.BattleState
import com.google.ar.core.examples.kotlin.helloar.model.battle.Fighter
import com.google.ar.core.examples.kotlin.helloar.model.battle.FighterID

/**
 * バトルのルール（攻撃・ダメージ・勝敗）。状態を受け取って新しい状態を返すだけで、タイマーや画面、通信は持たない
 * 自分の行動も相手の行動も同じ apply を通すので、どちらの端末で計算しても同じ結果になる
 * それぞれの人の HP を減らすのは、どちらか一方の人の行動だけ（通常攻撃は攻撃した人、必殺技は撃たれた人の結果）なので、
 * 2 人の行動が届く順番が端末ごとに違っても、HP は食い違わない
 * 数値は iOS の BattleEngine と完全に同じにする（違うと、iOS と対戦したときに HP が食い違う）
 */
object BattleEngine {
  /** 2 人とも同じ HP で始める */
  const val MAX_HP = 200
  const val NORMAL_ATTACK_DAMAGE = 10
  /** 通常攻撃のクールダウン（秒）。連打の制限は入力した端末で行うので、状態には持たない（スタミナも同じ） */
  const val NORMAL_ATTACK_COOLDOWN = 0.3
  /** 通常攻撃が当たるたびに溜まる必殺技ゲージ */
  const val SPECIAL_GAUGE_GAIN = 10
  const val SPECIAL_ATTACK_DAMAGE = 60
  /** 必殺技をよけたときのダメージ（ポケモン GO と同じく 4 分の 1） */
  const val DODGED_SPECIAL_DAMAGE = 15
  /**
   * フィーバーが終わってから、次のフィーバーの抽選を始めるまでに必要な通常攻撃の回数
   * ⚠️ 変えるときは iOS と、iOS の supabase/schema.sql の check_battle_action も合わせる
   */
  const val FEVER_MIN_ATTACKS = 5
  /**
   * フィーバーが続く時間（秒）。時間は行動した端末で数える（BattleViewModel）
   * ⚠️ 変えるときは iOS と、iOS の supabase/schema.sql の check_battle_action も合わせる
   */
  const val FEVER_DURATION = 5.0
  /** フィーバー中の通常攻撃のダメージの倍率 */
  const val FEVER_DAMAGE_MULTIPLIER = 2

  /** apply の結果。新しい状態と、与えたダメージ（ダメージのない行動は 0） */
  data class Applied(val state: BattleState, val damage: Int)

  /**
   * actor が action を行う。行えたら新しい状態とダメージを返す（ダメージを受けたのが誰かは action で決まる。BattleAction の hitsOpponent / resolvesSpecial）
   * ダメージのない行動（必殺技を撃つ、フィーバーを始める）はダメージ 0 を返す
   * 決着後やゲージ不足などで行えなければ null を返す
   */
  fun apply(action: BattleAction, actor: FighterID, state: BattleState): Applied? {
    if (state.winner != null) return null
    var me = state.fighters[actor] ?: return null
    val opponentID = state.opponentID(actor) ?: return null
    var opponent = state.fighters[opponentID] ?: return null

    val damage: Int
    when (action) {
      BattleAction.NORMAL_ATTACK -> {
        damage = minOf(NORMAL_ATTACK_DAMAGE, opponent.hp)
        opponent = opponent.copy(hp = opponent.hp - damage)
        me = me.copy(
          specialGauge = minOf(me.specialGauge + SPECIAL_GAUGE_GAIN, Fighter.SPECIAL_GAUGE_MAX),
          normalAttacksSinceFever = me.normalAttacksSinceFever + 1,
        )
      }
      BattleAction.FEVER_ATTACK -> {
        // フィーバー中の攻撃は、次のフィーバーまでの回数に数えない（続けてフィーバーにならないようにする）
        damage = minOf(NORMAL_ATTACK_DAMAGE * FEVER_DAMAGE_MULTIPLIER, opponent.hp)
        opponent = opponent.copy(hp = opponent.hp - damage)
        me = me.copy(specialGauge = minOf(me.specialGauge + SPECIAL_GAUGE_GAIN, Fighter.SPECIAL_GAUGE_MAX))
      }
      BattleAction.SPECIAL_ATTACK -> {
        if (!me.canUseSpecial) return null
        damage = 0
        me = me.copy(specialGauge = 0)
        opponent = opponent.copy(incomingSpecials = opponent.incomingSpecials + 1)
      }
      BattleAction.FEVER -> {
        if (!me.canUseFever) return null
        damage = 0
        me = me.copy(normalAttacksSinceFever = 0)
      }
      BattleAction.TAKE_SPECIAL, BattleAction.DODGE_SPECIAL -> {
        if (me.incomingSpecials <= 0) return null
        val full = if (action == BattleAction.TAKE_SPECIAL) SPECIAL_ATTACK_DAMAGE else DODGED_SPECIAL_DAMAGE
        damage = minOf(full, me.hp)
        me = me.copy(hp = me.hp - damage, incomingSpecials = me.incomingSpecials - 1)
      }
    }

    val winner = when {
      opponent.hp == 0 -> actor
      me.hp == 0 -> opponentID
      else -> null
    }
    return Applied(BattleState(state.fighters + mapOf(actor to me, opponentID to opponent), winner), damage)
  }
}
