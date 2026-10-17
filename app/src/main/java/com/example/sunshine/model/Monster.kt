package com.example.sunshine.model

/**
 * 対戦に使うモンスター
 * modelName は iOS の Monster.swift の Monster.all と同じにする。相手には modelName で送るので、違うと iOS は対戦しない
 */
data class Monster(val name: String, val modelName: String) {
    companion object {
        val all = listOf(
            Monster("ビーバー", "animal-beaver"),
            Monster("ハチ", "animal-bee"),
            Monster("ウサギ", "animal-bunny"),
            Monster("ネコ", "animal-cat"),
            Monster("イモムシ", "animal-caterpillar"),
            Monster("ヒヨコ", "animal-chick"),
            Monster("ウシ", "animal-cow"),
            Monster("カニ", "animal-crab"),
            Monster("シカ", "animal-deer"),
            Monster("イヌ", "animal-dog"),
            Monster("ゾウ", "animal-elephant"),
            Monster("サカナ", "animal-fish"),
            Monster("キツネ", "animal-fox"),
            Monster("キリン", "animal-giraffe"),
            Monster("イノシシ", "animal-hog"),
            Monster("コアラ", "animal-koala"),
            Monster("ライオン", "animal-lion"),
            Monster("サル", "animal-monkey"),
            Monster("パンダ", "animal-panda"),
            Monster("オウム", "animal-parrot"),
            Monster("ペンギン", "animal-penguin"),
            Monster("ブタ", "animal-pig"),
            Monster("シロクマ", "animal-polar"),
            Monster("トラ", "animal-tiger"),
        )

        /** モデル名からモンスターを探す。対戦相手のモンスターはモデル名で送られてくる */
        fun named(modelName: String): Monster? = all.firstOrNull { it.modelName == modelName }
    }
}
