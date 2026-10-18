package com.google.ar.core.examples.kotlin.helloar.service

import com.google.ar.core.examples.kotlin.helloar.model.Monster
import com.google.ar.core.examples.kotlin.helloar.model.battle.BattleMonster

/**
 * 捕まえたモンスターと、バトルに出すモンスターを保存する
 * 今は端末の中だけに保存する（PreferencesMonsterCollection）
 * 共有の DB に替えるときは、この interface の実装を差し替える
 */
interface MonsterCollection {
  /** 捕まえたモンスターを保存する。同じ種類をすでに持っていれば、件数は増えない */
  fun add(monster: Monster)

  /**
   * 最初に選んだモンスターを、捕まえたものとして保存する
   * 捕獲画面のモンスター（Monster）を BattleMonster にそろえたら、上の add と 1 つにする
   */
  fun add(monster: BattleMonster)

  /** バトルに出すモンスター。まだ選んでいなければ null */
  val battleMonster: BattleMonster?

  /** バトルに出すモンスターを決める（最初にモンスターを選んだとき） */
  fun setBattleMonster(monster: BattleMonster)
}
