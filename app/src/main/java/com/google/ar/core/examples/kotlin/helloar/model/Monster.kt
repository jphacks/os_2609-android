package com.google.ar.core.examples.kotlin.helloar.model

/**
 * モンスターのデータ（iOS の Monster と同じ 16 体）。捕獲画面とバトルで使う
 * 対戦相手には modelName を送るので、名前・modelName は iOS と完全に同じにする（違うと iOS で相手のモンスターがわからず、対戦できない）
 * 捕まえたモンスターも modelName で保存するので、捕まえた種類をそのままバトルに出せる
 */
data class Monster(
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
     * 出現する・バトルに出すモンスターの一覧。モデルは Quaternius「Ultimate Monsters」（CC0、assets/monsters/License.txt）
     * 攻撃のアニメーションは、何かを持っているモンスター（バニー、デーモン、キノコキング、オーク、スカルオーク）は
     * 通常攻撃で武器を振って必殺技で跳びかかる。ほかはパンチして、必殺技で武器を振る動きをする
     */
    val ALL = listOf(
      Monster("エイリアン", "monster-alien", 1.25f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      Monster("バード", "monster-birb", 1.05f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      Monster("ブルーデーモン", "monster-blue-demon", 1.55f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      Monster("バニー", "monster-bunny", 1.05f, MonsterAnimation.WEAPON, MonsterAnimation.JUMP),
      Monster("カクトロ", "monster-cactoro", 1.25f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      Monster("デーモン", "monster-demon", 1.65f, MonsterAnimation.WEAPON, MonsterAnimation.JUMP),
      Monster("ディノ", "monster-dino", 1.4f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      Monster("フィッシュ", "monster-fish", 1.15f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      Monster("フロッグ", "monster-frog", 1.0f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      Monster("モンクルース", "monster-monkroose", 1.2f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      Monster("キノコキング", "monster-mushroom-king", 1.55f, MonsterAnimation.WEAPON, MonsterAnimation.JUMP),
      Monster("ニンジャ", "monster-ninja", 1.25f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      Monster("オーク", "monster-orc", 1.45f, MonsterAnimation.WEAPON, MonsterAnimation.JUMP),
      Monster("スカルオーク", "monster-orc-skull", 1.55f, MonsterAnimation.WEAPON, MonsterAnimation.JUMP),
      Monster("トライバル", "monster-tribal", 1.35f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
      Monster("イエティ", "monster-yeti", 1.8f, MonsterAnimation.PUNCH, MonsterAnimation.WEAPON),
    )

    /** モデル名からモンスターを探す。対戦相手のモンスターはモデル名で送られてくる */
    fun named(modelName: String): Monster? = ALL.firstOrNull { it.modelName == modelName }
  }
}
