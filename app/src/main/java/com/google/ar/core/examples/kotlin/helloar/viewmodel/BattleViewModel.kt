package com.google.ar.core.examples.kotlin.helloar.viewmodel

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.examples.kotlin.helloar.logic.BattleEngine
import com.google.ar.core.examples.kotlin.helloar.logic.FeverLottery
import com.google.ar.core.examples.kotlin.helloar.model.battle.BattleAction
import com.google.ar.core.examples.kotlin.helloar.model.Monster
import com.google.ar.core.examples.kotlin.helloar.model.battle.BattleState
import com.google.ar.core.examples.kotlin.helloar.model.battle.Fighter
import com.google.ar.core.examples.kotlin.helloar.model.battle.FighterID
import com.google.ar.core.examples.kotlin.helloar.model.battle.Stamina
import com.google.ar.core.examples.kotlin.helloar.service.BattleService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 対戦画面の状態と、バトルの進行（iOS の BattleViewModel と同じ流れ）
 * 「バトルする」を押した人どうしをマッチングし（場所は問わない）、お互いにタップで攻撃し合う
 * 通常攻撃はスタミナを使い、使い切ると休憩になる。攻撃するたびにフィーバーの抽選をし、ときどきチャンスになる
 * 必殺技はゲージが満タンになると自動で撃つ。相手の必殺技は、予告している間にスワイプするとよけられる
 * 自分の行動はすぐに反映して相手に送り、相手の行動は届いた順に反映する（どちらも BattleEngine で計算する）
 *
 * @param service 対戦相手とのやり取り。アプリ全体で 1 つだけ作ったもの
 * @param appScope 画面を閉じたあとに対戦から抜けるのに使う。viewModelScope は閉じたときに止まってしまうため
 */
class BattleViewModel(
  private val service: BattleService,
  private val appScope: CoroutineScope,
) : ViewModel() {
  enum class Phase {
    /** 対戦相手を待っていない（「バトルする」を押す前） */
    LOBBY,
    /** 対戦相手を待っている */
    SEARCHING,
    /** 相手探しをやめて、片付けが終わるのを待っている */
    CANCELLING_SEARCH,
    /** 対戦中（決着がついて結果を出している間も含む） */
    FIGHTING,
  }

  /** 自分から見た決着 */
  enum class Outcome { WON, LOST }

  /**
   * 攻撃が当たった出来事。画面の演出（ダメージ表示、揺れ）のきっかけになる
   * 出来事ごとに別のものとして扱うので、data class にしない（同じダメージが続いても、別の出来事として演出を流す）
   */
  class Hit(action: BattleAction, val damage: Int, val isFinishing: Boolean) {
    /** 必殺技による攻撃か（演出を派手にする） */
    val isSpecial = action.resolvesSpecial
    /** よけられた必殺技か（のけぞらず、横にかわす） */
    val isDodged = action == BattleAction.DODGE_SPECIAL
    /** フィーバー中の通常攻撃か（演出を派手にする） */
    val isFever = action == BattleAction.FEVER_ATTACK

    /**
     * 攻撃を始めてから相手に当たるまでの時間（秒）。のけぞり、ダメージの数字、光、振動をこの分だけ遅らせる
     * 必殺技の溜めは結果が決まる前に流し終えているので、ここには含めない
     */
    val impactDelay: Double
      get() = if (isSpecial) SPECIAL_IMPACT_TIME else NORMAL_IMPACT_TIME

    companion object {
      /**
       * 通常攻撃で、タップしてから相手に当たるまでの時間（秒）
       * タップしてすぐに当たったと感じられるよう、攻撃の動きを待たずに、踏み込みが届くころに当てる
       */
      const val NORMAL_IMPACT_TIME = 0.1
      /** 必殺技で、攻撃の動きを流し始めてから相手に当たるまでの時間（秒） */
      const val SPECIAL_IMPACT_TIME = 0.25
      /** とどめが当たった瞬間に、2 体とも動きを止める時間（秒）（ヒットストップ） */
      const val HIT_STOP_DURATION = 0.2
      /** とどめを刺されて倒れる動きの速さ（スローモーション） */
      const val FINISHING_DEATH_SPEED = 0.5
    }
  }

  /** 相手の必殺技の予告。相手の必殺技が届いてから、受けたかよけたかを決めるまでの間だけある */
  class Danger

  /** よけるためにスワイプした出来事。予告の外でスワイプしても、スタミナを使って横に動く（よけたことにはならない） */
  class Dodge(
    /** 右へスワイプしたか。モンスターがかわす向きに使う */
    val toRight: Boolean,
  )

  /** 相手の必殺技の予告中にスワイプしたが、休憩中やスタミナが足りなくてよけられなかった出来事 */
  class FailedDodge(
    /** よけられなかった予告 */
    val danger: Danger,
  )

  /**
   * 次の対戦で使う自分のモンスター
   * 【仮】今はランダム（iOS と同じ）。捕まえたモンスターから選べるようにしたら、ここと leaveBattle() の選び直しを置き換える
   */
  var myMonster by mutableStateOf(Monster.ALL.random())
    private set
  /** 対戦中の相手のモンスター。マッチングするまでは null */
  var opponentMonster by mutableStateOf<Monster?>(null)
    private set
  var phase by mutableStateOf(Phase.LOBBY)
    private set
  /** 対戦中の状態。マッチングするまでは null */
  var state by mutableStateOf<BattleState?>(null)
    private set
  /** 確定した決着。決着がついていない、またはまだ確定していなければ null */
  var outcome by mutableStateOf<Outcome?>(null)
    private set
  /** 対戦できなかったとき（通信の失敗、相手が抜けた）に出すメッセージ */
  var errorMessage by mutableStateOf<String?>(null)
    private set
  /** 最後に相手へ当たった攻撃。画面はこれが変わるたびに演出を流す */
  var lastHitOnOpponent by mutableStateOf<Hit?>(null)
    private set
  /** 最後に自分へ当たった攻撃 */
  var lastHitOnMe by mutableStateOf<Hit?>(null)
    private set
  /** 予告している相手の必殺技。予告していなければ null */
  var incomingDanger by mutableStateOf<Danger?>(null)
    private set
  /** 最後によけるためにスワイプした出来事 */
  var lastDodge by mutableStateOf<Dodge?>(null)
    private set
  /** スワイプしてよけた予告。予告が終わったときにこれと同じなら、よけたことになる */
  private var dodgedDanger: Danger? = null
  /** 最後によけられなかった出来事。変わるたびに振動させ、予告の文字を「よけられない」に変える */
  var lastFailedDodge by mutableStateOf<FailedDodge?>(null)
    private set
  /** 自分のスタミナ。値は時間とともに変わるので、表示するときは value() で求める */
  var stamina by mutableStateOf(Stamina())
    private set
  /** 休憩が終わる時刻に、終わったことを stamina に記録するジョブ */
  private var restJob: Job? = null
  /** 見せているチャンス。チャンスでなければ null */
  var chance by mutableStateOf<FeverLottery.Chance?>(null)
    private set
  private var chanceJob: Job? = null
  /**
   * 自分のフィーバーが終わる時刻（SystemClock.elapsedRealtime）。フィーバー中でなければ null
   * 時刻で持つので、アプリがバックグラウンドにある間も時間は進む（戻ってきたときに残り時間が増えない）
   */
  var feverEndsAt by mutableStateOf<Long?>(null)
    private set
  /** フィーバーを終わらせるジョブ */
  private var feverJob: Job? = null
  /** この時刻（SystemClock.elapsedRealtime）になるまで次の通常攻撃はできない（クールダウン） */
  private var nextNormalAttackAt = 0L

  private var searchJob: Job? = null
  private var receiveJob: Job? = null
  private var finishJob: Job? = null
  /** 対戦をやめたときの片付け。次の相手探しは、これが終わってから始める */
  private var leaveJob: Job? = null

  val me: Fighter?
    get() = state?.fighters?.get(MY_ID)
  val opponent: Fighter?
    get() = state?.fighters?.get(OPPONENT_ID)
  /** フィーバー中か。終わる時刻を過ぎていたら、終わらせるジョブがまだ動いていなくてもフィーバーではない */
  val isFeverActive: Boolean
    get() = feverEndsAt?.let { now() < it } ?: false
  /** へとへとで休憩中か。休憩しているかは stamina だけが持つ（攻撃できるかの判断と画面の表示で食い違わないように） */
  val isResting: Boolean
    get() = stamina.isResting(now())

  /** 「バトルする」が押されたときに呼ぶ。対戦相手を探し、誰も待っていなければ相手が来るまで待つ */
  fun findOpponent() {
    if (phase != Phase.LOBBY) return
    phase = Phase.SEARCHING
    errorMessage = null
    val previousLeave = leaveJob
    searchJob = viewModelScope.launch {
      // 前の対戦の片付けが終わる前に始めると、片付けが新しい相手探しの状態まで消してしまう
      // 片付けを待っている間に「やめる」が押されていたら、ここでキャンセルされて相手を探さない
      previousLeave?.join()
      try {
        val opponentMonster = service.findOpponent(myMonster)
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
   * 「やめる」が押されたとき、またはバトルの画面が隠れたときに呼ぶ。対戦相手を探すのをやめる
   * 片付けが終わってからロビーに戻る
   * 終わる前に次の相手探しを始めると、前の片付けが新しい相手探しの状態まで消してしまうため
   * 相手を探していなければ（対戦中など）何もしない
   */
  fun cancelSearch() {
    val searchJob = searchJob ?: return
    if (phase != Phase.SEARCHING) return
    phase = Phase.CANCELLING_SEARCH
    searchJob.cancel()
    viewModelScope.launch {
      searchJob.join()
      // キャンセルと同時にマッチングが終わっていた場合に備えて、対戦からも抜ける
      service.leave()
      phase = Phase.LOBBY
    }
  }

  /**
   * 画面がタップされたときに呼ぶ。クールダウン中や休憩中は何も起きない
   * スタミナを使い切ったら、休憩を始める
   */
  fun attack() {
    val now = now()
    if (now < nextNormalAttackAt || phase != Phase.FIGHTING || finishJob != null || isResting) return
    val action = if (isFeverActive) BattleAction.FEVER_ATTACK else BattleAction.NORMAL_ATTACK
    if (!perform(action)) return
    nextNormalAttackAt = now + (BattleEngine.NORMAL_ATTACK_COOLDOWN * 1000).toLong()
    stamina = stamina.spendForAttack(now())
    if (isResting) {
      startRest()
    }
    // ゲージが満タンになったら、自動で必殺技を撃つ
    if (me?.canUseSpecial == true) {
      perform(BattleAction.SPECIAL_ATTACK)
    }
    if (action == BattleAction.NORMAL_ATTACK && chance == null && me?.canUseFever == true) {
      FeverLottery.draw()?.let(::startChance)
    }
  }

  /**
   * 画面が左右にスワイプされたときに呼ぶ。休憩中やスタミナが足りないときはよけられない
   * 相手の必殺技を予告している間なら、よけたことになる（よけられなかったら、そのことを知らせる）
   */
  fun dodge(toRight: Boolean) {
    if (phase != Phase.FIGHTING || finishJob != null) return
    val spent = stamina.spendForDodge(now())
    if (spent == null) {
      incomingDanger?.let { lastFailedDodge = FailedDodge(it) }
      return
    }
    stamina = spent
    lastDodge = Dodge(toRight)
    incomingDanger?.let { dodgedDanger = it }
  }

  /** 結果を見終わったときに呼ぶ。ロビーに戻る。次の対戦では別のモンスターを使う */
  fun leaveBattle() {
    reset()
    myMonster = Monster.ALL.random()
  }

  /** 画面を閉じたら、相手探しや対戦をやめる（アプリ全体の scope で、閉じたあとも最後まで片付ける） */
  override fun onCleared() {
    appScope.launch { service.leave() }
  }

  /**
   * チャンスを見せ、結果が出たら消す。当たりならフィーバーを始める
   * チャンスの間も攻撃はできるが、次の抽選はしない
   */
  private fun startChance(drawn: FeverLottery.Chance) {
    chance = drawn
    chanceJob = viewModelScope.launch {
      delay((chanceDuration(drawn) * 1000).toLong())
      chance = null
      if (drawn.willWin) {
        startFever()
      }
    }
  }

  /** FEVER_DURATION 秒の間、通常攻撃のダメージが 2 倍になる */
  private fun startFever() {
    if (isFeverActive || !perform(BattleAction.FEVER)) return
    val duration = (BattleEngine.FEVER_DURATION * 1000).toLong()
    feverEndsAt = now() + duration
    feverJob?.cancel()
    feverJob = viewModelScope.launch {
      delay(duration)
      feverEndsAt = null
    }
  }

  /**
   * 対戦や相手探しをやめて、ロビーの状態に戻す
   * 自分のモンスターはここでは選び直さない（対戦できなかったときは、同じモンスターでやり直す）
   */
  private fun reset() {
    searchJob?.cancel()
    receiveJob?.cancel()
    finishJob?.cancel()
    finishJob = null
    endFever()
    endRest()
    leaveJob = viewModelScope.launch { service.leave() }
    state = null
    opponentMonster = null
    outcome = null
    lastHitOnOpponent = null
    lastHitOnMe = null
    incomingDanger = null
    lastDodge = null
    dodgedDanger = null
    lastFailedDodge = null
    phase = Phase.LOBBY
  }

  private fun startBattle(opponentMonster: Monster) {
    this.opponentMonster = opponentMonster
    state = BattleState(
      Fighter(id = MY_ID, name = myMonster.name, maxHP = BattleEngine.MAX_HP),
      Fighter(id = OPPONENT_ID, name = opponentMonster.name, maxHP = BattleEngine.MAX_HP),
    )
    nextNormalAttackAt = now()
    stamina = Stamina()
    phase = Phase.FIGHTING
    receiveOpponentActions()
  }

  /** 決着がつくまで相手の行動を受け取って反映する */
  private fun receiveOpponentActions() {
    receiveJob = viewModelScope.launch {
      try {
        // false を返すと受け取りをやめる（決着がついたとき）
        service.opponentActions().takeWhile { action ->
          val applied = state?.let { BattleEngine.apply(action, OPPONENT_ID, it) } ?: return@takeWhile true
          state = applied.state
          recordHit(action, OPPONENT_ID, applied.damage)
          if (applied.state.winner != null) return@takeWhile false
          // 相手が必殺技を撃ったら予告し、その間にスワイプしたかどうかで、受けたかよけたかを送り返す
          // 予告している間に届いた相手の行動は、そのあとに順番に反映する
          if (action == BattleAction.SPECIAL_ATTACK) {
            val danger = Danger()
            incomingDanger = danger
            delay((DANGER_DURATION * 1000).toLong())
            incomingDanger = null
            perform(if (dodgedDanger === danger) BattleAction.DODGE_SPECIAL else BattleAction.TAKE_SPECIAL)
            if (state?.winner != null) return@takeWhile false
          }
          true
        }.collect {}
        // 自分の端末で決着がついた、または相手の端末で決着がついて受け取りが終わった
        if (!isActive) return@launch
        confirmOutcome()
      } catch (e: CancellationException) {
        // 自分でやめたとき（leaveBattle）や、自分の攻撃で決着がついたときはエラーにしない
        throw e
      } catch (e: Exception) {
        fail(e)
      }
    }
  }

  /** 自分の行動を反映して相手に伝える。行えたら true */
  private fun perform(action: BattleAction): Boolean {
    if (phase != Phase.FIGHTING || finishJob != null) return false
    val applied = state?.let { BattleEngine.apply(action, MY_ID, it) } ?: return false
    state = applied.state
    recordHit(action, MY_ID, applied.damage)
    service.send(action)
    if (applied.state.winner != null) {
      confirmOutcome()
    }
    return true
  }

  /**
   * ダメージを与えた行動なら、受けた側への攻撃として記録する（画面の演出のきっかけになる）
   * 通常攻撃は行動した人の相手が、必殺技の結果は行動した本人がダメージを受ける
   */
  private fun recordHit(action: BattleAction, actor: FighterID, damage: Int) {
    val target = when {
      action.hitsOpponent -> if (actor == MY_ID) OPPONENT_ID else MY_ID
      action.resolvesSpecial -> actor
      else -> return
    }
    val hit = Hit(action, damage, isFinishing = state?.winner != null)
    if (target == MY_ID) {
      lastHitOnMe = hit
    } else {
      lastHitOnOpponent = hit
    }
  }

  /** 相手の行動の受け取りをやめて、決着を確定させる */
  private fun confirmOutcome() {
    if (finishJob != null) return
    receiveJob?.cancel()
    incomingDanger = null
    endFever()
    endRest()
    val iWon = state?.winner == MY_ID
    finishJob = viewModelScope.launch {
      try {
        val confirmed = service.finish(iWon)
        outcome = if (confirmed) Outcome.WON else Outcome.LOST
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        fail(e)
      }
    }
  }

  private fun endFever() {
    chanceJob?.cancel()
    chanceJob = null
    chance = null
    feverJob?.cancel()
    feverJob = null
    feverEndsAt = null
  }

  /**
   * へとへとになったときに呼ぶ。休憩が終わる時刻まで待って、終わったことを stamina に記録する
   * 待つ時間は stamina の休憩が終わる時刻から求める（別に数えると、少しずれて食い違う）
   */
  private fun startRest() {
    val restEndsAt = stamina.restEndsAt ?: return
    restJob?.cancel()
    restJob = viewModelScope.launch {
      delay(restEndsAt - now())
      stamina = stamina.finishRest()
    }
  }

  private fun endRest() {
    restJob?.cancel()
    restJob = null
    stamina = stamina.finishRest()
  }

  /** 相手がいなくなった・通信に失敗したときは、対戦をやめてロビーに戻る */
  private fun fail(error: Exception) {
    // ⚠️ 開発用：原因を調べるためのログ。リリースするときに消してよい
    Log.w(TAG, "対戦の通信に失敗しました", error)
    reset()
    errorMessage = "対戦できませんでした。もう一度お試しください"
  }

  private fun now() = SystemClock.elapsedRealtime()

  companion object {
    private const val TAG = "BattleViewModel"

    /** 状態の中で自分と相手を見分ける ID。どちらの端末でも、自分が MY_ID になる */
    private const val MY_ID: FighterID = "me"
    private const val OPPONENT_ID: FighterID = "opponent"

    /** チャンスの帯が、色を 1 段階上げるまでの時間（秒） */
    const val CHANCE_STEP_DURATION = 0.7
    /** 帯の色が止まってから、結果（砕ける・金に光る）を見せる時間（秒） */
    const val CHANCE_RESULT_DURATION = 0.9

    /** 相手の必殺技が届いてから、受けたかよけたかを決めるまでの時間（秒）。この間、画面に「DANGER!」を出し、スワイプするとよけられる */
    const val DANGER_DURATION = 1.0

    /**
     * チャンスになってから結果が出るまでの時間（秒）。上の色まで上がるほど長くなる
     * ChanceComponent はこの時間に合わせて色を上げる
     */
    fun chanceDuration(chance: FeverLottery.Chance): Double =
      CHANCE_STEP_DURATION * (chance.finalLevel.ordinal + 1) + CHANCE_RESULT_DURATION
  }
}
