package com.example.sunshine.ui.battle

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sunshine.logic.BattleEngine
import com.example.sunshine.model.BattleAction
import com.example.sunshine.model.BattleState
import com.example.sunshine.model.Fighter
import com.example.sunshine.model.FighterID
import com.example.sunshine.model.Monster
import com.example.sunshine.service.BattleService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 対戦画面の状態と、バトルの進行（iOS の BattleViewModel と同じ流れ）
 * 同じ地点にいる相手とマッチングし、お互いにタップで攻撃し合う
 * 自分の行動はすぐに反映して相手に送り、相手の行動は届いた順に反映する（どちらも BattleEngine で計算する）
 *
 * viewModelScope は Dispatchers.Main で動くので、状態はすべてメインスレッドだけで読み書きする
 */
class BattleViewModel(private val service: BattleService) : ViewModel() {
    enum class Phase {
        /** 対戦相手を探す前 */
        Lobby,
        /** 対戦相手を探している */
        Searching,
        /** 相手探しをやめて、片付けが終わるのを待っている */
        CancellingSearch,
        /** 対戦中（決着がついて結果を出している間も含む） */
        Fighting,
    }

    /** 自分から見た決着 */
    enum class Outcome { Won, Lost }

    /** 攻撃が当たった出来事。画面の演出（ダメージ表示、揺れ）のきっかけになる */
    data class Hit(
        val damage: Int,
        /** 必殺技による攻撃か（演出を派手にする） */
        val isSpecial: Boolean,
        val id: UUID = UUID.randomUUID(),
    )

    /**
     * 次の対戦で使う自分のモンスター
     * 【仮】今はランダム。捕まえたモンスターから選べるようにしたら、ここと leaveBattle() の選び直しを置き換える
     */
    var myMonster by mutableStateOf(Monster.all.random())
        private set
    var phase by mutableStateOf(Phase.Lobby)
        private set
    /** 対戦中の状態。マッチングするまでは null */
    var state by mutableStateOf<BattleState?>(null)
        private set
    /** 確定した決着。決着がついていない、またはまだ確定していなければ null */
    var outcome by mutableStateOf<Outcome?>(null)
        private set
    /** 通信に失敗したときに出すメッセージ */
    var errorMessage by mutableStateOf<String?>(null)
        private set
    /** 最後に相手へ当たった攻撃。画面はこれが変わるたびに演出を流す */
    var lastHitOnOpponent by mutableStateOf<Hit?>(null)
        private set
    /** 最後に自分へ当たった攻撃 */
    var lastHitOnMe by mutableStateOf<Hit?>(null)
        private set

    /** この時刻（SystemClock.uptimeMillis）になるまで次の通常攻撃はできない（クールダウン） */
    private var nextNormalAttackAt = 0L

    private var searchJob: Job? = null
    private var receiveJob: Job? = null
    private var finishJob: Job? = null

    val me: Fighter? get() = state?.fighters?.get(MY_ID)
    val opponent: Fighter? get() = state?.fighters?.get(OPPONENT_ID)

    /** 同じ地点にいる対戦相手を探す */
    fun findOpponent(locationID: String) {
        if (phase != Phase.Lobby) return
        phase = Phase.Searching
        errorMessage = null
        searchJob = viewModelScope.launch {
            try {
                val opponentMonster = service.findOpponent(locationID, myMonster)
                startBattle(opponentMonster)
            } catch (e: CancellationException) {
                // 自分でやめたとき（cancelSearch）はエラーにしない
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /**
     * 対戦相手を探すのをやめる
     * 片付けが終わってからロビーに戻る
     * 終わる前に次の相手探しを始めると、前の片付けが新しい相手探しの状態まで消してしまうため
     */
    fun cancelSearch() {
        val job = searchJob
        if (phase != Phase.Searching || job == null) return
        phase = Phase.CancellingSearch
        job.cancel()
        viewModelScope.launch {
            job.join()
            // キャンセルと同時にマッチングが終わっていた場合に備えて、対戦からも抜ける
            service.leave()
            phase = Phase.Lobby
        }
    }

    /** 画面がタップされたときに呼ぶ。クールダウン中は何も起きない */
    fun attack() {
        val now = SystemClock.uptimeMillis()
        if (now < nextNormalAttackAt) return
        if (perform(BattleAction.NormalAttack)) {
            nextNormalAttackAt = now + BattleEngine.NORMAL_ATTACK_COOLDOWN_MILLIS
        }
    }

    /** 必殺技ボタンが押されたときに呼ぶ。ゲージが満タンでなければ何も起きない */
    fun useSpecial() {
        perform(BattleAction.SpecialAttack)
    }

    /** 対戦をやめてロビーに戻る。次の対戦では別のモンスターを使う */
    fun leaveBattle() {
        searchJob?.cancel()
        receiveJob?.cancel()
        finishJob?.cancel()
        finishJob = null
        viewModelScope.launch { service.leave() }
        state = null
        outcome = null
        lastHitOnOpponent = null
        lastHitOnMe = null
        myMonster = Monster.all.random()
        phase = Phase.Lobby
    }

    /**
     * 画面を閉じたとき（戻るボタンなど）に、対戦から抜ける
     * 抜けないと対戦が playing のまま残り、相手は 60 秒待ってからやっと気づく。Realtime の購読も残り続ける
     * （iOS はタブの画面で閉じることがないので、Android だけにある）
     * viewModelScope はこの時点でもう止まっているので、画面と関係なく最後まで動く GlobalScope で行う
     */
    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        GlobalScope.launch(Dispatchers.Main) { service.leave() }
    }

    private fun startBattle(opponentMonster: Monster) {
        state = BattleState(
            Fighter(id = MY_ID, name = myMonster.name, maxHP = BattleEngine.MAX_HP),
            Fighter(id = OPPONENT_ID, name = opponentMonster.name, maxHP = BattleEngine.MAX_HP),
        )
        nextNormalAttackAt = 0L
        phase = Phase.Fighting
        receiveOpponentActions()
    }

    /** 決着がつくまで相手の行動を受け取って反映する */
    private fun receiveOpponentActions() {
        receiveJob = viewModelScope.launch {
            try {
                service.opponentActions().collect { action ->
                    val current = state ?: return@collect
                    val result = BattleEngine.apply(action, OPPONENT_ID, current) ?: return@collect
                    state = result.state
                    lastHitOnMe = Hit(result.damage, isSpecial = action == BattleAction.SpecialAttack)
                    // 相手のとどめで決着した。confirmOutcome がこの受け取りを止める
                    if (result.state.winner != null) confirmOutcome()
                }
                // 相手の端末で決着がついて、受け取りが終わった
                ensureActive()
                confirmOutcome()
            } catch (e: CancellationException) {
                // 自分でやめたとき（leaveBattle）や、決着がついて止めたときはエラーにしない
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** 自分の行動を反映して相手に伝える。行えたら true */
    private fun perform(action: BattleAction): Boolean {
        val current = state
        if (phase != Phase.Fighting || finishJob != null || current == null) return false
        val result = BattleEngine.apply(action, MY_ID, current) ?: return false
        state = result.state
        lastHitOnOpponent = Hit(result.damage, isSpecial = action == BattleAction.SpecialAttack)
        service.send(action)
        if (result.state.winner != null) confirmOutcome()
        return true
    }

    /** 相手の行動の受け取りをやめて、決着を確定させる */
    private fun confirmOutcome() {
        if (finishJob != null) return
        receiveJob?.cancel()
        val iWon = state?.winner == MY_ID
        finishJob = viewModelScope.launch {
            try {
                val confirmed = service.finish(iWon)
                outcome = if (confirmed) Outcome.Won else Outcome.Lost
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** 相手が見つからない・相手がいなくなった・通信に失敗したときは、対戦をやめてロビーに戻る */
    private fun fail(error: Exception) {
        // ⚠️ 開発用：原因を調べるためのログ。リリースするときに消してよい
        Log.w(TAG, "対戦の通信に失敗しました", error)
        leaveBattle()
        errorMessage = "対戦できませんでした。もう一度お試しください"
    }

    private companion object {
        const val TAG = "BattleViewModel"
        /** 状態の中で自分と相手を見分ける ID。どちらの端末でも、自分が MY_ID になる */
        const val MY_ID: FighterID = "me"
        const val OPPONENT_ID: FighterID = "opponent"
    }
}
