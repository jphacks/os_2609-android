package com.google.ar.core.examples.kotlin.helloar

import android.app.Application
import com.google.ar.core.examples.kotlin.helloar.service.MonsterCollection
import com.google.ar.core.examples.kotlin.helloar.service.PreferencesMonsterCollection

/**
 * アプリ全体で使うものを持つ（iOS の ARAppApp）
 * 捕まえたモンスターの保存先は、画面ごとに作らず、アプリ全体で 1 つだけ作って各画面で使う
 */
class ARAppApplication : Application() {
  val monsterCollection: MonsterCollection by lazy { PreferencesMonsterCollection(this) }
}
