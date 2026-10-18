package com.google.ar.core.examples.kotlin.helloar.model.battle

import com.google.ar.core.examples.kotlin.helloar.logic.BattleEngine

/** バトルの状態の中で、戦う人を見分ける ID */
typealias FighterID = String

/** バトルで戦う 1 人ぶんの状態。自分も相手も同じ形で持つ */
data class Fighter(
  val id: FighterID,
  val name: String,
  val maxHP: Int,
  val hp: Int = maxHP,
  /** 必殺技ゲージ（0〜SPECIAL_GAUGE_MAX）。満タンになると、自動で必殺技を撃つ */
  val specialGauge: Int = 0,
  /** 前のフィーバーのあとに、フィーバーでない通常攻撃をした回数。少ないうちはフィーバーの抽選をしない */
  val normalAttacksSinceFever: Int = 0,
  /** 撃たれたのに、まだ結果（受けた・よけた）を送っていない相手の必殺技の数 */
  val incomingSpecials: Int = 0,
) {
  /** ゲージが満タンなら必殺技を使える */
  val canUseSpecial: Boolean
    get() = specialGauge >= SPECIAL_GAUGE_MAX

  /** フィーバーを始められるか（続けてフィーバーにならないよう、間に通常攻撃を挟む） */
  val canUseFever: Boolean
    get() = normalAttacksSinceFever >= BattleEngine.FEVER_MIN_ATTACKS

  companion object {
    const val SPECIAL_GAUGE_MAX = 100
  }
}
