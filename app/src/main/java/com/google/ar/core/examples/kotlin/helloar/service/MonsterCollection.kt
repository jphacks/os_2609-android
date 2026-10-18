package com.google.ar.core.examples.kotlin.helloar.service

import com.google.ar.core.examples.kotlin.helloar.model.Monster

/**
 * 捕まえたモンスターを保存する
 * 今は端末の中だけに保存する（PreferencesMonsterCollection）
 * 共有の DB に替えるときは、この interface の実装を差し替える
 */
interface MonsterCollection {
  /** 捕まえたモンスターを保存する。同じ種類をすでに持っていれば、件数は増えない */
  fun add(monster: Monster)
}
