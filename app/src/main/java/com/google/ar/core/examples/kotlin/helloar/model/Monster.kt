package com.google.ar.core.examples.kotlin.helloar.model

/** AR 空間に出現するモンスターのデータ */
data class Monster(
  val name: String,
  /** 見た目に使う 3D モデルのファイル名（assets/models に置いた .glb。拡張子は付けない） */
  val modelName: String,
  /**
   * 体の大きさ（メートル）。モデルの高さがこの長さになる。モンスターごとに変えて、大小の違いを出す
   * ARCore の 1.0 は現実の 1m
   */
  val size: Float,
) {
  /** assets の中での 3D モデルの場所 */
  val modelAssetPath: String
    get() = "models/$modelName.glb"

  companion object {
    /**
     * 出現するモンスターの一覧。モデルは Kenney「Cube Pets」（CC0、assets/models/License.txt）
     * iOS（Quaternius のモンスター）とはモデルが違うので、modelName も iOS とは別になっている
     */
    val ALL = listOf(
      Monster("ビーバー", "animal-beaver", 0.6f),
      Monster("ハチ", "animal-bee", 0.5f),
      Monster("ウサギ", "animal-bunny", 0.6f),
      Monster("ネコ", "animal-cat", 0.5f),
      Monster("イモムシ", "animal-caterpillar", 0.4f),
      Monster("ヒヨコ", "animal-chick", 0.4f),
      Monster("ウシ", "animal-cow", 1.2f),
      Monster("カニ", "animal-crab", 0.4f),
      Monster("シカ", "animal-deer", 1.2f),
      Monster("イヌ", "animal-dog", 0.6f),
      Monster("ゾウ", "animal-elephant", 1.6f),
      Monster("サカナ", "animal-fish", 0.5f),
      Monster("キツネ", "animal-fox", 0.6f),
      Monster("キリン", "animal-giraffe", 1.8f),
      Monster("イノシシ", "animal-hog", 0.8f),
      Monster("コアラ", "animal-koala", 0.6f),
      Monster("ライオン", "animal-lion", 1.1f),
      Monster("サル", "animal-monkey", 0.7f),
      Monster("パンダ", "animal-panda", 1.0f),
      Monster("オウム", "animal-parrot", 0.5f),
      Monster("ペンギン", "animal-penguin", 0.7f),
      Monster("ブタ", "animal-pig", 0.7f),
      Monster("シロクマ", "animal-polar", 1.2f),
      Monster("トラ", "animal-tiger", 1.1f),
    )
  }
}
