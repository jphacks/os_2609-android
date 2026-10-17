// ⚠️⚠️⚠️ 開発用のコード ⚠️⚠️⚠️
// Supabase の鍵がないとき（local.properties に書いていないとき）に、本物の対戦相手の代わりに使う。
// 本番では使わないが、鍵を持っていないメンバーの環境で対戦画面が動かなくなるので、iOS と同じく残しておく。
// 削除するときは、MainActivity の battleService() で DummyBattleService を選んでいる所も一緒に消す。
package com.example.sunshine.service

import com.example.sunshine.model.BattleAction
import com.example.sunshine.model.Monster
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 【開発用】Supabase がなくても対戦を試せるダミー実装
 * すぐに相手が見つかり、相手（CPU）は一定の間隔で通常攻撃してくる
 */
class DummyBattleService : BattleService {
    override suspend fun findOpponent(locationID: String, monster: Monster): Monster {
        delay(1_500)
        return Monster.all.random()
    }

    // 受け取る側が止まったら（決着した、対戦をやめた）、攻撃も止まる
    override fun opponentActions(): Flow<BattleAction> = flow {
        while (true) {
            delay(ATTACK_INTERVAL_MILLIS)
            emit(BattleAction.NormalAttack)
        }
    }

    override fun send(action: BattleAction) {
        // CPU はこちらの行動を受け取る必要がない
    }

    override suspend fun finish(iWon: Boolean): Boolean = iWon

    override suspend fun leave() {}

    private companion object {
        /** CPU が攻撃する間隔（ミリ秒） */
        const val ATTACK_INTERVAL_MILLIS = 1_000L
    }
}
