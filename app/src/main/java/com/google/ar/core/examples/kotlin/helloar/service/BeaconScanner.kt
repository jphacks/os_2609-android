package com.google.ar.core.examples.kotlin.helloar.service

/**
 * ビーコンの電波を受信して、名前と RSSI を知らせる
 * 実機では [BleBeaconScanner]、ESP32 がないときは [DummyBeaconScanner] を使う
 */
interface BeaconScanner {
  /** ビーコンの広告パケットを受信するたびに、メインスレッドで呼ばれる */
  var onReceive: ((beaconName: String, rssi: Int) -> Unit)?

  fun start()
  fun stop()
}
