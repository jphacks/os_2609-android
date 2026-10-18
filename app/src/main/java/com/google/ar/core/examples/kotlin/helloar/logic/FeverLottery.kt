package com.google.ar.core.examples.kotlin.helloar.logic

import kotlin.random.Random

/**
 * フィーバーの抽選（パチンコのように、攻撃するたびに抽選して、ときどきチャンスになる）
 * チャンスになったら、画面を横切る帯の色が 青 → 緑 → 赤 → 金 と上がっていき、金まで上がればフィーバー
 * どこまで上がるかは、チャンスになった時点で決める
 * 抽選は攻撃した端末で行い、当たったら fever を相手に送る
 */
object FeverLottery {
  /** 帯の色。上の段階ほど、当たり（金）に近い */
  enum class Level { BLUE, GREEN, RED, GOLD }

  /** 通常攻撃 1 回でチャンスになる確率 */
  const val CHANCE_RATE = 0.2

  /**
   * チャンスになったとき、帯がどの色まで上がるかの確率（足すと 1）
   * 赤まで上がったら、3 回に 2 回は金になる（赤が出たら期待できる）
   */
  private val FINAL_LEVEL_RATES = listOf(
    Level.BLUE to 0.2,
    Level.GREEN to 0.2,
    Level.RED to 0.2,
    Level.GOLD to 0.4,
  )

  /** 抽選した結果のチャンス。チャンスになってから結果が出るまで、BattleViewModel が持つ */
  class Chance(
    /** 帯がこの色まで上がって止まる */
    val finalLevel: Level,
  ) {
    /** 当たり（金まで上がる）か */
    val willWin: Boolean
      get() = finalLevel == Level.GOLD
  }

  /** 通常攻撃 1 回ぶん抽選する。チャンスにならなければ null */
  fun draw(): Chance? {
    if (Random.nextDouble() >= CHANCE_RATE) return null
    var roll = Random.nextDouble()
    for ((level, rate) in FINAL_LEVEL_RATES) {
      if (roll < rate) return Chance(level)
      roll -= rate
    }
    return Chance(Level.GOLD)
  }
}
