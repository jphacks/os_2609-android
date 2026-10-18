package com.google.ar.core.examples.kotlin.helloar.model.battle

/**
 * バトルに出すモンスター（iOS の Monster と同じ 16 体）
 * 対戦相手には modelName を送るので、名前・modelName は iOS と完全に同じにする（違うと iOS で相手のモンスターがわからず、対戦できない）
 * 捕獲画面のモンスター（model.Monster、Kenney の動物）は iOS と modelName が違うので、バトルではこちらを使う
 */
data class BattleMonster(
  val name: String,
  /** 見た目に使う 3D モデルのファイル名（assets/monsters に置いた .glb。拡張子は付けない） */
  val modelName: String,
  /** 体の大きさ（メートル）。モデルの高さがこの長さになる。ARCore の 1.0 は現実の 1m */
  val size: Float,
  /** 通常攻撃のときに流すアニメーション */
  val normalAttack: MonsterAnimation,
  /** 必殺技のときに流すアニメーション */
  val specialAttack: MonsterAnimation,
) {
  /** assets の中での 3D モデルの場所 */
  val modelAssetPath: String
    get() = "monsters/$modelName.glb"

  companion object {
    /**
     * バトルに出すモンスターの一覧。モデルは Quaternius「Ultimate Monsters」（CC0、assets/monsters/License.txt）
     * 攻撃のアニメーションは、何かを持っているモンスター（バニー、デーモン、キノコキング、オーク、スカルオーク）は
     * 通常攻撃で武器を振って必殺技で跳びかかる。ほかはパンチして、必殺技で武器を振る動きをする
     */
    val ALL = listOf(
      BattleMonster("エイリアン", "monster-alien", 1.25f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      BattleMonster("バード", "monster-birb", 1.05f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      BattleMonster("ブルーデーモン", "monster-blue-demon", 1.55f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      BattleMonster("バニー", "monster-bunny", 1.05f, MonsterAnimation.WEAPON, MonsterAnimation.JUMP),
      BattleMonster("カクトロ", "monster-cactoro", 1.25f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      BattleMonster("デーモン", "monster-demon", 1.65f, MonsterAnimation.WEAPON, MonsterAnimation.JUMP),
      BattleMonster("ディノ", "monster-dino", 1.4f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      BattleMonster("フィッシュ", "monster-fish", 1.15f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      BattleMonster("フロッグ", "monster-frog", 1.0f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      BattleMonster("モンクルース", "monster-monkroose", 1.2f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      BattleMonster("キノコキング", "monster-mushroom-king", 1.55f, MonsterAnimation.WEAPON, MonsterAnimation.JUMP),
      BattleMonster("ニンジャ", "monster-ninja", 1.25f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      BattleMonster("オーク", "monster-orc", 1.45f, MonsterAnimation.WEAPON, MonsterAnimation.JUMP),
      BattleMonster("スカルオーク", "monster-orc-skull", 1.55f, MonsterAnimation.WEAPON, MonsterAnimation.JUMP),
      BattleMonster("トライバル", "monster-tribal", 1.35f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      BattleMonster("イエティ", "monster-yeti", 1.8f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
    )

    /** モデル名からモンスターを探す。対戦相手のモンスターはモデル名で送られてくる */
    fun named(modelName: String): BattleMonster? = ALL.firstOrNull { it.modelName == modelName }
  }
}
