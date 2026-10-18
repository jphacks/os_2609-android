package com.google.ar.core.examples.kotlin.helloar.service

import android.os.SystemClock
import android.util.Log
import com.google.ar.core.examples.kotlin.helloar.model.battle.BattleAction
import com.google.ar.core.examples.kotlin.helloar.model.battle.BattleMonster
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecordOrNull
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
* Supabase で対戦相手とやり取りする（iOS の SupabaseBattleService と同じ流れ）
* テーブルと関数の定義は iOS のリポジトリの supabase/schema.sql にある
* - 相手探し：join_battle 関数を呼ぶ。待っている人がいれば（場所は問わない）入り、いなければ部屋を作って待つ
*   待っている間は keep_waiting 関数で、まだ待っていることを定期的に伝える。伝えなくなった部屋には誰も入らない
* - 行動：battle_actions テーブルに 1 回ずつ番号（seq）を付けて書き込み、相手の書き込みを Realtime で受け取る
*   相手の行動は番号順に流す。Realtime がつながらない・取りこぼしたときは、2 秒ごとにデータベースを直接見て取り直す
* - 決着：finish_battle 関数で勝った人を記録する。2 人とも記録しようとしたら、先に記録した方に決まる
*
* 状態はすべてメインスレッドだけで読み書きする（呼び出し元の viewModelScope も、裏の処理の scope も Dispatchers.Main）
*/
class SupabaseBattleService(url: String, anonKey: String) : BattleService {
  sealed class ServiceError(message: String) : Exception(message) {
    /** 相手の行動が一定時間届かない、または相手が対戦をやめた */
    class OpponentLeft : ServiceError("相手がいなくなった")
    /** 届いたモンスター名や行動が、アプリの知らない値だった */
    class InvalidData : ServiceError("知らない値が届いた")
    /** 参加中の対戦がないのに、行動を受け取ろうとした・決着を記録しようとした */
    class NotInBattle : ServiceError("対戦に参加していない")
    /** 自分の行動を送れなかった。相手の端末と HP が食い違うので、対戦を続けられない */
    class SendFailed : ServiceError("行動を送れなかった")
  }

  private val client = createSupabaseClient(url, anonKey) {
    install(Postgrest)
    install(Realtime)
  }
  /** Realtime の受け取りや、行動の書き込みなど、裏で動かす処理 */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  /** 自分を見分ける ID。ログインは使わず、アプリを起動するたびに作り直す */
  private val playerID = UUID.randomUUID().toString()

  /** 参加中の対戦の ID */
  private var battleID: String? = null
  /** 相手が入ってきたら入る、相手のプレイヤー ID とモンスター名 */
  private var opponentPlayerID: String? = null
  private var opponentMonsterName: String? = null
  /** 参加中の対戦の変更を受け取る Realtime のチャンネル */
  private var channel: RealtimeChannel? = null
  private var listenJob: Job? = null
  /** 対戦が終わった（相手がやめた、またはどちらかが決着を記録した） */
  private var isBattleFinished = false
  /** 記録された勝者。相手がやめて終わったときは null */
  private var winnerID: String? = null

  /** 相手の行動を流す先。opponentActions() を集め始めてから、流し終わるまである */
  private var actions: ProducerScope<BattleAction>? = null
  private var watchJob: Job? = null
  /** 届いたがまだ流していない相手の行動（番号ごと）。番号が飛んでいたら、間が届くまで待つ */
  private val pendingActions = mutableMapOf<Int, BattleAction>()
  /** 最後に流した相手の行動の番号（1 から） */
  private var lastDeliveredSeq = 0
  /** 最後に相手の行動が届いた時刻（SystemClock.elapsedRealtime）。相手がいなくなったかの判断に使う */
  private var lastReceivedAt = 0L

  /** 書き込みが終わっていない自分の行動（古い順） */
  private val outgoing = mutableListOf<ActionRow>()
  /** 次に送る自分の行動の番号 */
  private var nextSeq = 1
  private var sendJob: Job? = null
  private var didFailToSend = false

  override suspend fun findOpponent(monster: BattleMonster): BattleMonster {
    leave()
    try {
      return joinAndWait(monster)
    } catch (e: Throwable) {
      // キャンセルされたり失敗したりしたら、作った部屋が相手待ちのまま残らないように片付ける
      // キャンセルされたコルーチンの中で通信すると通信まで取り消されるので、NonCancellable で最後まで行う
      withContext(NonCancellable) { leave() }
      throw e
    }
  }

  /** join_battle で部屋に入り（または作り）、相手がそろうまで待つ */
  private suspend fun joinAndWait(monster: BattleMonster): BattleMonster {
    // 途中でキャンセルされても通信は最後まで行い、部屋の ID を受け取ってから片付けられるようにする
    // （通信ごと取り消すと、サーバーには部屋ができたのに ID がわからず消せなくなる）
    val params = buildJsonObject {
      put("p_player_id", playerID)
      put("p_monster", monster.modelName)
    }
    val row = withContext(NonCancellable) {
      client.postgrest.rpc("join_battle", params).decodeAs<BattleRow>()
    }
    battleID = row.id
    currentCoroutineContext().ensureActive()

    if (row.player1ID == playerID) {
      receive(row)
    } else {
      opponentPlayerID = row.player1ID
      opponentMonsterName = row.player1Monster
    }
    startListening(row.id)

    // 自分が部屋を作った場合は、相手が入ってくるまで（キャンセルされるまで）待ち続ける
    // 待っている間は、まだ待っていることを定期的に伝える
    // 下の確認とは別のコルーチンで伝える。同じループで順番に送ると、確認の通信が止まっている間（会場の Wi-Fi が悪いなど）に
    // 伝えられなくなり、部屋が使われなくなってしまう
    val keepWaitingJob = scope.launch {
      val params = buildJsonObject { put("p_battle_id", row.id) }
      while (true) {
        delay(KEEP_WAITING_INTERVAL_MILLIS)
        // 通信が一瞬切れても失敗にせず、次の回で伝える
        orNull { client.postgrest.rpc("keep_waiting", params) }
      }
    }
    try {
      // Realtime を取りこぼしたときのために、ときどきデータベースも直接見る（購読を始める前に入ってきた場合もここで気づく）
      var nextFetch = SystemClock.elapsedRealtime()
      while (opponentMonsterName == null) {
        if (SystemClock.elapsedRealtime() >= nextFetch) {
          // 通信が一瞬切れても失敗にせず、次の確認を待つ
          orNull { fetchBattle(row.id) }?.let(::receive)
          nextFetch = SystemClock.elapsedRealtime() + FALLBACK_FETCH_INTERVAL_MILLIS
        }
        delay(200)
      }
    } finally {
      keepWaitingJob.cancel()
    }

    return opponentMonsterName?.let(BattleMonster::named) ?: throw ServiceError.InvalidData()
  }

  override fun opponentActions(): Flow<BattleAction> = callbackFlow {
    val battleID = battleID ?: throw ServiceError.NotInBattle()
    actions = this
    lastReceivedAt = SystemClock.elapsedRealtime()

    // 流し始める前に Realtime で届いていた行動を流す
    deliverPendingActions()
    if (isBattleFinished) {
      scope.launch { endAfterLastActions() }
    } else {
      watchJob = scope.launch { watch(battleID) }
    }
    // 受け取る側が止まったら（決着した、対戦をやめた）、データベースを見に行くのもやめる
    // 止まるのが遅れて、次の対戦の受け取りがもう始まっていたら、そちらは止めない
    val producer = this
    awaitClose { if (actions === producer) endActions() }
  }.buffer(Channel.UNLIMITED)

  override fun send(action: BattleAction) {
    val battleID = battleID ?: return
    outgoing += ActionRow(battleID, playerID, nextSeq, action.rawValue)
    nextSeq += 1
    if (sendJob == null) {
      sendJob = scope.launch { flush() }
    }
  }

  override suspend fun finish(iWon: Boolean): Boolean {
    val battleID = battleID ?: throw ServiceError.NotInBattle()
    val opponentPlayerID = opponentPlayerID ?: throw ServiceError.NotInBattle()
    // とどめの行動より先に決着が相手に伝わらないよう、行動を送り終えてから記録する
    sendJob?.join()
    if (didFailToSend) throw ServiceError.SendFailed()

    val params = buildJsonObject {
      put("p_battle_id", battleID)
      put("p_winner_id", if (iWon) playerID else opponentPlayerID)
    }
    val winner = retrying { client.postgrest.rpc("finish_battle", params).decodeAs<String?>() }
    return winner == playerID
  }

  override suspend fun leave() {
    // 先に状態を空にしてから通信する。通信を待っている間に次の相手探しが始まっても、新しい対戦に触れないようにする
    val leavingBattleID = battleID
    val leavingChannel = channel
    listenJob?.cancel()
    listenJob = null
    channel = null
    sendJob?.cancel()
    sendJob = null
    endActions()
    battleID = null
    opponentPlayerID = null
    opponentMonsterName = null
    isBattleFinished = false
    winnerID = null
    pendingActions.clear()
    lastDeliveredSeq = 0
    outgoing.clear()
    nextSeq = 1
    didFailToSend = false

    if (leavingChannel != null) {
      orNull { client.realtime.removeChannel(leavingChannel) }
    }
    // 相手待ちの部屋が残らないように、対戦を終わったことにする。対戦中なら相手にも伝わる
    // 決着がついたあとなら、記録された勝者はそのまま残る
    if (leavingBattleID != null) {
      // p_winner_id は送らない（関数側の既定値 null になる）
      val params = buildJsonObject { put("p_battle_id", leavingBattleID) }
      orNull { client.postgrest.rpc("finish_battle", params) }
    }
  }

  // 自分の行動を送る

  /**
   * 書き込みが終わっていない行動を、古い順にまとめて書き込む
   * 書き込んでいる間に増えた行動は、次の回でまとめて書き込む
   * 対戦をやめた（leave でキャンセルされた）ときは、CancellationException でそのまま抜ける
   */
  private suspend fun flush() {
    while (outgoing.isNotEmpty()) {
      val rows = outgoing.toList()
      try {
        // 書き込めたのに返事だけ届かなかった場合に備えて、同じ番号の行がすでにあれば何もしない（ON CONFLICT DO NOTHING）
        retrying {
          client.from("battle_actions").upsert(rows) {
            onConflict = "battle_id,player_id,seq"
            ignoreDuplicates = true
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        didFailToSend = true
        outgoing.clear()
        endActions(ServiceError.SendFailed())
        break
      }
      outgoing.subList(0, rows.size).clear()
    }
    sendJob = null
  }

  /** 通信が一瞬切れても対戦が終わらないように、少し待ってやり直す */
  private suspend fun <T> retrying(operation: suspend () -> T): T {
    var attempt = 1
    while (true) {
      try {
        return operation()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (attempt >= ATTEMPTS) throw e
        attempt += 1
        delay(RETRY_DELAY_MILLIS)
      }
    }
  }

  // 相手の行動と対戦の変更を受け取る

  /**
   * 対戦の変更（相手が入った・終わった）と、相手の行動を Realtime で受け取り始める
   * 購読は裏で行い、終わるのを待たない
   * 会場の Wi-Fi などで Realtime（WebSocket）がつながらなくても、データベースの定期確認で代わりに受け取れるので対戦は続けられる
   */
  private fun startListening(battleID: String) {
    val channel = client.channel("battle-$battleID")
    // 受け取りたい変更は、購読を始める前に登録しておく
    val battleUpdates = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
      table = "battles"
      filter("id", FilterOperator.EQ, battleID)
    }
    val actionInserts = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
      table = "battle_actions"
      filter("battle_id", FilterOperator.EQ, battleID)
    }
    // 購読の途中で対戦をやめても leave() で閉じられるよう、先に持っておく
    this.channel = channel

    listenJob = scope.launch {
      battleUpdates.onEach { update -> update.decodeRecordOrNull<BattleRow>()?.let(::receive) }.launchIn(this)
      actionInserts.onEach { insert -> insert.decodeRecordOrNull<ActionRow>()?.let(::receive) }.launchIn(this)
      // subscribe は接続に失敗しても例外を出さず、つながるまで裏で再接続を続ける
      // そのため、しばらく購読できないときにログを出して気づけるようにする
      launch {
        val subscribed = withTimeoutOrNull(REALTIME_WARNING_DELAY_MILLIS) {
          channel.status.first { it == RealtimeChannel.Status.SUBSCRIBED }
        }
        if (subscribed == null) {
          // ⚠️ 開発用：原因を調べるためのログ。リリースするときに消してよい
          Log.w(TAG, "Realtime に接続できていません。データベースの定期確認だけで続けます")
        }
      }
      try {
        channel.subscribe()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // 設定の誤りなどで購読そのものができないとき。アプリを落とさず、データベースの定期確認だけで続ける
        // ⚠️ 開発用：原因を調べるためのログ。リリースするときに消してよい
        Log.w(TAG, "Realtime の購読に失敗しました。データベースの定期確認だけで続けます", e)
      }
    }
  }

  /** Realtime で届かなかった行動や、相手がいなくなったことに気づけるように、ときどきデータベースを直接見る */
  private suspend fun watch(battleID: String) {
    while (true) {
      delay(FALLBACK_FETCH_INTERVAL_MILLIS)
      // 通信が一瞬切れても失敗にせず、次の確認を待つ（相手がいなくなったかはタイムアウトで判断する）
      orNull { fetchOpponentActions(battleID) }
      orNull { fetchBattle(battleID) }?.let(::receive)
      if (!isBattleFinished && SystemClock.elapsedRealtime() - lastReceivedAt > OPPONENT_TIMEOUT_MILLIS) {
        endActions(ServiceError.OpponentLeft())
        return
      }
    }
  }

  /** やめた対戦の通信が遅れて届くことがあるので、参加中の対戦のものだけ受け取る */
  private fun receive(row: BattleRow) {
    if (row.id != battleID) return
    if (row.player1ID == playerID && row.player2Monster != null) {
      opponentPlayerID = row.player2ID
      opponentMonsterName = row.player2Monster
    }
    if (row.status != "finished" || isBattleFinished) return
    isBattleFinished = true
    winnerID = row.winnerID
    // 行動を流している途中なら、残りを流してから終える。まだ流し始めていなければ、opponentActions() で終える
    if (actions != null) {
      scope.launch { endAfterLastActions() }
    }
  }

  private fun receive(row: ActionRow) {
    if (row.battleID != battleID || row.playerID == playerID ||
      row.seq <= lastDeliveredSeq || pendingActions.containsKey(row.seq)
    ) return
    // 知らない行動を飛ばして先へ進むと、相手の端末と HP が食い違うので、対戦を続けない
    val action = BattleAction.fromRawValue(row.action)
    if (action == null) {
      endActions(ServiceError.InvalidData())
      return
    }
    pendingActions[row.seq] = action
    lastReceivedAt = SystemClock.elapsedRealtime()
    deliverPendingActions()
  }

  /** 次の番号の行動から順に流す。番号が飛んでいたら、そこで止めて間が届くのを待つ */
  private fun deliverPendingActions() {
    val actions = actions ?: return
    while (true) {
      val action = pendingActions.remove(lastDeliveredSeq + 1) ?: break
      lastDeliveredSeq += 1
      actions.trySend(action)
    }
  }

  /**
   * 対戦が終わったら、残っている相手の行動を流してから終える
   * 相手は行動を送り終えてから決着を記録するが、Realtime で取りこぼしていた場合に備えて、もう一度だけデータベースを見る
   */
  private suspend fun endAfterLastActions() {
    val endingBattleID = battleID ?: return
    orNull { fetchOpponentActions(endingBattleID) }
    // 確認している間に対戦をやめて次の対戦が始まっていたら、新しい対戦を終わらせない
    if (battleID != endingBattleID) return
    if (winnerID == null) {
      endActions(ServiceError.OpponentLeft())
    } else {
      endActions()
    }
  }

  /** 相手の行動を流すのをやめる。error を渡すと、受け取る側に例外で伝わる。何度呼んでもよい */
  private fun endActions(error: Throwable? = null) {
    watchJob?.cancel()
    watchJob = null
    actions?.close(error)
    actions = null
  }

  private suspend fun fetchBattle(id: String): BattleRow =
    client.from("battles").select {
      filter { eq("id", id) }
    }.decodeSingle()

  private suspend fun fetchOpponentActions(battleID: String) {
    val rows = client.from("battle_actions").select {
      filter {
        eq("battle_id", battleID)
        neq("player_id", playerID)
        gt("seq", lastDeliveredSeq)
      }
      order("seq", Order.ASCENDING)
    }.decodeList<ActionRow>()
    rows.forEach(::receive)
  }

  /** 失敗しても null にして続ける。キャンセルだけはそのまま伝える */
  private suspend fun <T> orNull(block: suspend () -> T): T? =
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }

  private companion object {
    const val TAG = "SupabaseBattleService"
    /**
     * 部屋を作って待っている間、まだ待っていることを伝える間隔（ミリ秒）
     * join_battle は 10 秒以上伝えてこない部屋を使わないので、通信が 1〜2 回途切れても間に合うように短くする
     */
    const val KEEP_WAITING_INTERVAL_MILLIS = 3_000L
    /** 相手の行動がこの時間届かなければ、相手がいなくなったとみなす（ミリ秒） */
    const val OPPONENT_TIMEOUT_MILLIS = 60_000L
    /** 書き込みに失敗したとき、何回まで試すか */
    const val ATTEMPTS = 3
    const val RETRY_DELAY_MILLIS = 500L
    /** Realtime を取りこぼしたときのために、データベースを直接見に行く間隔（ミリ秒） */
    const val FALLBACK_FETCH_INTERVAL_MILLIS = 2_000L
    /** Realtime の購読がこの時間たっても終わらなければ、つながっていないとみなしてログを出す（ミリ秒） */
    const val REALTIME_WARNING_DELAY_MILLIS = 10_000L
  }
}

// テーブルの行（supabase/schema.sql と対応する）。uuid は小文字の文字列で届く

@Serializable
private data class BattleRow(
  val id: String,
  val status: String,
  @SerialName("player1_id") val player1ID: String,
  @SerialName("player1_monster") val player1Monster: String,
  @SerialName("player2_id") val player2ID: String? = null,
  @SerialName("player2_monster") val player2Monster: String? = null,
  @SerialName("winner_id") val winnerID: String? = null,
)

@Serializable
private data class ActionRow(
  @SerialName("battle_id") val battleID: String,
  @SerialName("player_id") val playerID: String,
  val seq: Int,
  val action: String,
)
