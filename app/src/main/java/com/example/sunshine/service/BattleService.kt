package com.example.sunshine.service

import com.example.sunshine.model.BattleAction
import com.example.sunshine.model.Monster
import kotlinx.coroutines.flow.Flow

/**
 * 対戦相手とのやり取り（iOS の BattleService と同じ操作）
 * 本番は SupabaseBattleService、Supabase の鍵がないときは DummyBattleService（CPU が相手）を使う
 */
interface BattleService {
    /**
     * 同じ地点で対戦相手を探す。誰も待っていなければ、自分が待つ側になって相手が来るまで待つ
     * 待っている間にコルーチンがキャンセルされたら、待つのをやめる
     * 見つかった相手のモンスターを返す
     */
    suspend fun findOpponent(locationID: String, monster: Monster): Monster

    /**
     * 相手の行動が、相手が行った順に流れてくる
     * 決着がつくと終わり、相手が対戦をやめたり通信できなくなったりすると例外で終わる
     */
    fun opponentActions(): Flow<BattleAction>

    /** 自分の行動を相手に送る。待たずに戻り、送るのは裏で順番に行う */
    fun send(action: BattleAction)

    /**
     * 決着を伝え、確定した結果（自分が勝ったか）を返す
     * 2 人がほぼ同時にとどめを刺すと、端末ごとに自分の勝ちと計算してしまうので、先に伝えた方の結果にそろえる
     */
    suspend fun finish(iWon: Boolean): Boolean

    /** 対戦や相手探しをやめる */
    suspend fun leave()
}
