package com.google.ar.core.examples.kotlin.helloar.service

import android.content.Context
import com.google.ar.core.examples.kotlin.helloar.model.Monster
import com.google.ar.core.examples.kotlin.helloar.model.battle.BattleMonster

/**
 * 捕まえたモンスターと、バトルに出すモンスターを SharedPreferences に保存する（端末の中だけ）
 * 捕まえたモンスターは modelName の集合で持つので、同じ種類を何度捕まえても 1 件のまま
 * バトルに出すモンスターは modelName を 1 つだけ持つ
 */
class PreferencesMonsterCollection(context: Context) : MonsterCollection {
  companion object {
    private const val PREFERENCES_NAME = "monster_collection"
    private const val KEY_CAPTURED = "captured_model_names"
    private const val KEY_BATTLE_MONSTER = "battle_monster_model_name"
  }

  private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  override fun add(monster: Monster) {
    addCaptured(monster.modelName)
  }

  override fun add(monster: BattleMonster) {
    addCaptured(monster.modelName)
  }

  override val battleMonster: BattleMonster?
    get() = preferences.getString(KEY_BATTLE_MONSTER, null)?.let(BattleMonster::named)

  override fun setBattleMonster(monster: BattleMonster) {
    preferences.edit().putString(KEY_BATTLE_MONSTER, monster.modelName).apply()
  }

  private fun addCaptured(modelName: String) {
    // getStringSet が返す集合は書き換えてはいけないので、新しい集合を作って保存する
    val captured = preferences.getStringSet(KEY_CAPTURED, emptySet()).orEmpty()
    preferences.edit().putStringSet(KEY_CAPTURED, captured + modelName).apply()
  }
}
