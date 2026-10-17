package com.example.sunshine.model

/** バトルの状態の中で、戦う人を見分ける ID */
typealias FighterID = String

/** バトルで戦う 1 人ぶんの状態。自分も相手も同じ形で持つ */
data class Fighter(
    val id: FighterID,
    val name: String,
    val maxHP: Int,
    val hp: Int = maxHP,
    /** 必殺技ゲージ（0〜SPECIAL_GAUGE_MAX） */
    val specialGauge: Int = 0,
) {
    /** ゲージが満タンなら必殺技を使える */
    val canUseSpecial: Boolean get() = specialGauge >= SPECIAL_GAUGE_MAX

    companion object {
        const val SPECIAL_GAUGE_MAX = 100
    }
}
