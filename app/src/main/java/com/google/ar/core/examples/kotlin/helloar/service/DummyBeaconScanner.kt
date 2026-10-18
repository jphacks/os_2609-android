//
// ⚠️⚠️⚠️ 開発用のコード ⚠️⚠️⚠️
// ESP32 がないとき（エミュレータ）に、本物のビーコンの代わりに使う。
// 本番では使わないが、エミュレータで動かなくなるので削除しない。
// 使っている場所：MapActivity の makeScanner()（エミュレータのとき）
// 実機で試すときは、手元だけ makeScanner() をこれに差し替える（コミットしない）
//
package com.google.ar.core.examples.kotlin.helloar.service

import android.os.SystemClock
import com.google.ar.core.examples.kotlin.helloar.model.Location
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 【開発用】ESP32 がなくても動かせるダミー実装
 * 渡した地点を順番に巡り続け、その場所の RSSI のパターンに少し揺れを加えて、本物のビーコンのように流し続ける
 * 最後の地点の次は最初の地点へ戻る。歩いている間は、2 地点のパターンを歩いた割合で混ぜて流す（地点と地点の間にいる状態を作る）
 */
class DummyBeaconScanner(
  locations: List<Location>,
  /** 1 つの地点に立ち止まる時間（秒） */
  private val stayDuration: Double = 4.0,
  /** 次の地点まで歩く時間（秒） */
  private val walkDuration: Double = 6.0,
) : BeaconScanner {
  /** 巡る地点の RSSI のパターン。1 つだけならそこに立ち続ける */
  private val route = locations.map { it.fingerprint }

  override var onReceive: ((beaconName: String, rssi: Int) -> Unit)? = null

  private val scope: CoroutineScope = MainScope()
  private var emitJob: Job? = null

  override fun start() {
    if (emitJob != null) return
    val startedAt = SystemClock.elapsedRealtime()
    emitJob = scope.launch(Dispatchers.Main) {
      while (isActive) {
        val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        Location.BEACON_NAMES.zip(fingerprint(elapsed)).forEach { (name, rssi) ->
          onReceive?.invoke(name, rssi + (-3..3).random())
        }
        delay(200)
      }
    }
  }

  override fun stop() {
    emitJob?.cancel()
    emitJob = null
  }

  /** 流し始めてからの経過時間に対する RSSI のパターン */
  private fun fingerprint(elapsed: Double): List<Int> {
    if (route.size <= 1) return route[0]

    // 「立ち止まる → 次の地点へ歩く」を 1 区間として、全区間を繰り返す
    val legDuration = stayDuration + walkDuration
    val time = elapsed % (legDuration * route.size)
    val leg = (time / legDuration).toInt()
    val from = route[leg]
    val to = route[(leg + 1) % route.size]

    val walked = time - leg * legDuration - stayDuration
    if (walked <= 0) return from
    val ratio = walked / walkDuration
    return from.zip(to).map { (a, b) -> (a + (b - a) * ratio).roundToInt() }
  }
}
