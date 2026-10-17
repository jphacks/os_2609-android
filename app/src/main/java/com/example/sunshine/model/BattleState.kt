package com.example.sunshine.model

/** バトル全体の状態。自分の行動も相手の行動も同じルールで反映できるよう、「自分」「敵」ではなく ID で持つ */
data class BattleState(
    val fighters: Map<FighterID, Fighter>,
    /** 勝った人の ID。決着がついていなければ null */
    val winner: FighterID? = null,
) {
    /** 1 対 1 のバトルを始める */
    constructor(first: Fighter, second: Fighter) : this(mapOf(first.id to first, second.id to second))

    /** id の人の対戦相手の ID */
    fun opponentID(of: FighterID): FighterID? = fighters.keys.firstOrNull { it != of }
}
