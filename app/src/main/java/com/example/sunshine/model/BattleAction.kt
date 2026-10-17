package com.example.sunshine.model

/** バトル中に取れる行動。オンライン対戦では、この rawValue を相手に送る（battle_actions テーブルの action 列） */
enum class BattleAction(val rawValue: String) {
    NormalAttack("normalAttack"),
    SpecialAttack("specialAttack");

    companion object {
        /** 届いた文字列から行動を探す。知らない値なら null */
        fun fromRawValue(value: String): BattleAction? = entries.firstOrNull { it.rawValue == value }
    }
}
