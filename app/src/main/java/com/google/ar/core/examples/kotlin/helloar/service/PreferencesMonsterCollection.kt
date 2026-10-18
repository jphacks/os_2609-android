package com.google.ar.core.examples.kotlin.helloar.service

import android.content.Context
import com.google.ar.core.examples.kotlin.helloar.model.Monster

/**
 * 捕まえたモンスターを SharedPreferences に保存する（端末の中だけ）
 * モンスターの modelName の集合で持つので、同じ種類を何度捕まえても 1 件のまま
 */
class PreferencesMonsterCollection(context: Context) : MonsterCollection {
  companion object {
    private const val PREFERENCES_NAME = "monster_collection"
    private const val KEY_CAPTURED = "captured_model_names"
  }

  private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  override fun add(monster: Monster) {
    // getStringSet が返す集合は書き換えてはいけないので、新しい集合を作って保存する
    val captured = preferences.getStringSet(KEY_CAPTURED, emptySet()).orEmpty()
    preferences.edit().putStringSet(KEY_CAPTURED, captured + monster.modelName).apply()
  }
}
