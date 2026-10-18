package com.google.ar.core.examples.kotlin.helloar.model.battle

/**
 * バトル中に取れる行動。オンライン対戦では、この rawValue を相手に送る（battle_actions テーブルの action 列）
 * rawValue は iOS の BattleAction と完全に同じにする
 * フィーバーの時間（5 秒）は行動した端末で数えるので、フィーバー中の通常攻撃は別の行動（feverAttack）として送る
 * 受け取った側は時間を数えずに、届いた行動のとおりにダメージを計算する。正しく送られているかはサーバーが確かめる（iOS の supabase/schema.sql）
 */
enum class BattleAction(val rawValue: String) {
  NORMAL_ATTACK("normalAttack"),
  /**
   * 必殺技を撃つ（ダメージはまだ与えない）
   * よけたかどうかは撃たれた側の端末にしかわからないので、撃たれた側が結果（takeSpecial / dodgeSpecial）を送り返し、そこでダメージが決まる
   */
  SPECIAL_ATTACK("specialAttack"),
  /** フィーバーを始める（ダメージは与えない） */
  FEVER("fever"),
  /** フィーバー中の通常攻撃（ダメージ 2 倍） */
  FEVER_ATTACK("feverAttack"),
  /** 相手の必殺技をよけられずに受けた（行動した本人がダメージを受ける） */
  TAKE_SPECIAL("takeSpecial"),
  /** 相手の必殺技をよけた（行動した本人が、少しだけダメージを受ける） */
  DODGE_SPECIAL("dodgeSpecial");

  /** 相手にダメージを与える行動か */
  val hitsOpponent: Boolean
    get() = this == NORMAL_ATTACK || this == FEVER_ATTACK

  /** 相手の必殺技の結果か（行動した本人がダメージを受ける） */
  val resolvesSpecial: Boolean
    get() = this == TAKE_SPECIAL || this == DODGE_SPECIAL

  companion object {
    fun fromRawValue(rawValue: String): BattleAction? = entries.firstOrNull { it.rawValue == rawValue }
  }
}
