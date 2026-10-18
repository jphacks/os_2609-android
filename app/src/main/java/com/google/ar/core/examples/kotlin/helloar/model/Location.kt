package com.google.ar.core.examples.kotlin.helloar.model

/**
 * 現在地の候補になる地点
 * 位置推定に使う RSSI のパターン（フィンガープリント）と、マップに表示する位置をまとめて持つ
 */
data class Location(
  /** 場所 ID（例："entrance"） */
  val id: String,
  val name: String,
  /** マップ上でこの地点を表示する位置 */
  val position: MapPosition,
  /** この地点での各ビーコンの RSSI（dBm）。並びは [BEACON_NAMES] と同じ */
  val fingerprint: List<Int>,
) {
  companion object {
    /** 位置推定に使うビーコンの名前。RSSI のリストは常にこの順に並べる */
    val BEACON_NAMES = listOf("BeaconA", "BeaconB", "BeaconC", "BeaconD")

    /**
     * 登録済みの地点。位置は会場の見取り図に合わせて調整する
     * RSSI の数値は仮のものなので、実際の場所で測った値に置き換える
     * ブースA〜C は 2026-10-17 に試しに測った値（BeaconA〜C が起動、D は未起動。-100 は受信できなかったもの）
     * iOS の Location.registered と同じ値にしておく
     */
    val REGISTERED = listOf(
      Location("entrance", "入口", MapPosition(0.5, 0.88), listOf(-52, -67, -81, -73)),
      Location("booth_a", "ブースA", MapPosition(0.22, 0.58), listOf(-47, -100, -63, -100)),
      Location("booth_b", "ブースB", MapPosition(0.78, 0.58), listOf(-71, -86, -64, -100)),
      Location("booth_c", "ブースC", MapPosition(0.22, 0.2), listOf(-69, -100, -39, -100)),
      Location("stairs", "階段", MapPosition(0.8, 0.2), listOf(-76, -70, -61, -47)),
    )
  }
}
