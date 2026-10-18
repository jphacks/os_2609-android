package com.google.ar.core.examples.kotlin.helloar.service

import com.google.ar.core.examples.kotlin.helloar.model.Monster

/**
 * 捕まえたモンスターと、バトルに出すモンスターを保存する
 * 今は端末の中だけに保存する（PreferencesMonsterCollection）
 * 共有の DB に替えるときは、この interface の実装を差し替える
 */
interface MonsterCollection {
  /** 捕まえたモンスター（最初に選んだモンスターも）を保存する。同じ種類をすでに持っていれば、件数は増えない */
  fun add(monster: Monster)

  /** バトルに出すモンスター。まだ選んでいなければ null */
  val battleMonster: Monster?

  /** バトルに出すモンスターを決める（最初にモンスターを選んだとき） */
  fun setBattleMonster(monster: Monster)
}
