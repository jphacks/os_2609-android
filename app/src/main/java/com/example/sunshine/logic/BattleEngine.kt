package com.example.sunshine.logic

import com.example.sunshine.model.BattleAction
import com.example.sunshine.model.BattleState
import com.example.sunshine.model.Fighter
import com.example.sunshine.model.FighterID

/**
 * バトルのルール（攻撃・ダメージ・勝敗）。状態を受け取って新しい状態を返すだけで、タイマーや画面、通信は持たない
 * 自分の行動も相手の行動も同じ apply を通すので、どちらの端末で計算しても同じ結果になる
 * 数値は iOS の BattleEngine.swift と同じにする（1 つでも違うと、対戦中に 2 人の画面の HP が食い違う）
 */
object BattleEngine {
    /** 2 人とも同じ HP で始める */
    const val MAX_HP = 300
    const val NORMAL_ATTACK_DAMAGE = 10
    /** 通常攻撃のクールダウン（ミリ秒）。連打の制限は入力した端末で行うので、状態には持たない */
    const val NORMAL_ATTACK_COOLDOWN_MILLIS = 300L
    /** 通常攻撃が当たるたびに溜まる必殺技ゲージ */
    const val SPECIAL_GAUGE_GAIN = 10
    const val SPECIAL_ATTACK_DAMAGE = 50

    /** apply の結果。反映したあとの状態と、与えたダメージ */
    data class Result(val state: BattleState, val damage: Int)

    /** attacker が action を行う。決着後やゲージ不足で行えなければ null を返す */
    fun apply(action: BattleAction, attacker: FighterID, state: BattleState): Result? {
        if (state.winner != null) return null
        val me = state.fighters[attacker] ?: return null
        val targetID = state.opponentID(attacker) ?: return null
        val target = state.fighters[targetID] ?: return null

        val damage: Int
        val newMe: Fighter
        when (action) {
            BattleAction.NormalAttack -> {
                damage = minOf(NORMAL_ATTACK_DAMAGE, target.hp)
                newMe = me.copy(specialGauge = minOf(me.specialGauge + SPECIAL_GAUGE_GAIN, Fighter.SPECIAL_GAUGE_MAX))
            }
            BattleAction.SpecialAttack -> {
                if (!me.canUseSpecial) return null
                damage = minOf(SPECIAL_ATTACK_DAMAGE, target.hp)
                newMe = me.copy(specialGauge = 0)
            }
        }

        val newTarget = target.copy(hp = target.hp - damage)
        val newState = state.copy(
            fighters = state.fighters + (attacker to newMe) + (targetID to newTarget),
            winner = if (newTarget.hp == 0) attacker else null,
        )
        return Result(newState, damage)
    }
}
