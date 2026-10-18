package com.google.ar.core.examples.kotlin.helloar.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.ar.core.examples.kotlin.helloar.model.Location
import com.google.ar.core.examples.kotlin.helloar.model.MapPosition
import com.google.ar.core.examples.kotlin.helloar.service.BeaconScanner
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 推定した現在地。ビーコンを 1 台も受信できていない間は、どちらも null */
data class LocationState(
  val currentLocation: Location? = null,
  /** マップに自分を表示する位置。地点と地点の間にいるときは、その間になる */
  val currentPosition: MapPosition? = null,
) {
  val statusMessage: String
    get() = currentLocation?.let { "現在地：${it.name}" } ?: "ビーコンを探しています…"
}

/**
 * ビーコンの RSSI から現在地を推定する
 * 一定時間 RSSI を集めてはビーコンごとの中央値で推定し、集めた値を捨てて次を集め直す
 * （前の測定と重ねないので、推定が前の場所に引きずられない）
 */
class LocationViewModel(private val scanner: BeaconScanner) : ViewModel() {
  companion object {
    /** 1 回の推定のために RSSI を集める時間（ミリ秒）。この間隔で推定し直す */
    private const val MEASUREMENT_DURATION_MS = 2_000L
    /** 期間内に 1 回も受信できなかったビーコンの RSSI（とても遠い扱い） */
    private const val MISSING_RSSI = -100
    /** マップ上の位置を求めるときに使う、RSSI のパターンが近い地点の数 */
    private const val POSITION_NEIGHBOR_COUNT = 3

    fun factory(scanner: BeaconScanner): ViewModelProvider.Factory = viewModelFactory {
      initializer { LocationViewModel(scanner) }
    }

    /**
     * 重み付き k 近傍法でマップ上の位置を求める
     * RSSI のパターンが近い上位 [POSITION_NEIGHBOR_COUNT] 地点の位置を、パターンが近いほど重くして平均する
     */
    private fun weightedPosition(rssi: List<Int>): MapPosition? {
      val neighbors = Location.REGISTERED
        .map { it.position to distance(it.fingerprint, rssi) }
        .sortedBy { it.second }
        .take(POSITION_NEIGHBOR_COUNT)

      // 距離の 2 乗の逆数で重み付けし、ある地点に立っているときは点がほぼその地点に乗るようにする
      // （1 乗だと他の地点にも引っ張られ、地点の上に立っていても点がずれる）
      // 距離が 0 でも割れるように 1 を足す
      val weighted = neighbors.map { (position, distance) -> position to 1 / (distance + 1).pow(2) }
      val totalWeight = weighted.sumOf { it.second }
      if (totalWeight <= 0) return null

      return MapPosition(
        x = weighted.sumOf { (position, weight) -> position.x * weight } / totalWeight,
        y = weighted.sumOf { (position, weight) -> position.y * weight } / totalWeight,
      )
    }

    /**
     * RSSI のリストどうしのユークリッド距離。小さいほどパターンが似ている
     * （メートル単位の距離ではない）
     */
    private fun distance(a: List<Int>, b: List<Int>): Double =
      sqrt(a.zip(b).sumOf { (x, y) -> (x - y).toDouble().pow(2) })

    private fun median(values: List<Int>): Int? {
      if (values.isEmpty()) return null
      val sorted = values.sorted()
      val middle = sorted.size / 2
      return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }
  }

  private val _state = MutableStateFlow(LocationState())
  val state: StateFlow<LocationState> = _state.asStateFlow()

  /** ビーコンごとの、今の測定で受信した RSSI */
  private val samples = mutableMapOf<String, MutableList<Int>>()
  private var updateJob: Job? = null

  fun start() {
    if (updateJob != null) return
    scanner.onReceive = ::record
    scanner.start()

    updateJob = viewModelScope.launch {
      while (isActive) {
        delay(MEASUREMENT_DURATION_MS)
        updateEstimate()
      }
    }
  }

  fun stop() {
    scanner.stop()
    scanner.onReceive = null
    updateJob?.cancel()
    updateJob = null
    samples.clear()
  }

  override fun onCleared() {
    stop()
  }

  /** ビーコンを受信するたびに呼ばれる。対象のビーコンだけ記録する */
  private fun record(beaconName: String, rssi: Int) {
    if (beaconName !in Location.BEACON_NAMES) return
    samples.getOrPut(beaconName) { mutableListOf() }.add(rssi)
  }

  /** この [MEASUREMENT_DURATION_MS] に集めた RSSI から現在地を推定し直し、次の測定のために集めた値を捨てる */
  private fun updateEstimate() {
    // 1 台も受信できていないときは、どの地点とも言えない
    if (samples.values.all { it.isEmpty() }) {
      _state.value = LocationState()
      return
    }

    // ビーコンごとに中央値を取る。RSSI は揺れが大きいので、外れ値に強い中央値を使う
    val rssi = Location.BEACON_NAMES.map { median(samples[it].orEmpty()) ?: MISSING_RSSI }
    samples.clear()

    // RSSI のパターンが一番似ている（距離が一番小さい）地点を現在地とする
    _state.value = LocationState(
      currentLocation = Location.REGISTERED.minByOrNull { distance(it.fingerprint, rssi) },
      currentPosition = weightedPosition(rssi),
    )
  }
}
