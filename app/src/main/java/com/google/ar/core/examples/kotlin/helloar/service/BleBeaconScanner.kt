package com.google.ar.core.examples.kotlin.helloar.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

/**
 * Android の BLE スキャンで ESP32 のビーコンを受信する
 * 接続はせず、流れてくる広告パケットを受信するだけ
 * 権限（BLUETOOTH_SCAN など）は、start() を呼ぶ前に画面側でもらっておく
 */
class BleBeaconScanner(context: Context) : BeaconScanner {
  companion object {
    /** ESP32 側の SERVICE_UUID と同じ値。この UUID を広告しているデバイスだけを受信する */
    private val SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
  }

  override var onReceive: ((beaconName: String, rssi: Int) -> Unit)? = null

  private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
  private var isScanning = false

  /** 広告パケットを受信するたびに呼ばれる。コールバックはメインスレッドで届く */
  private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      // device.name は BLUETOOTH_CONNECT の権限が要るので、広告パケットに入っている名前を使う
      val name = result.scanRecord?.deviceName ?: return
      onReceive?.invoke(name, result.rssi)
    }
  }

  @SuppressLint("MissingPermission")
  override fun start() {
    if (isScanning) return
    // Bluetooth がオフのときは scanner が null になる
    val scanner = bluetoothManager?.adapter?.bluetoothLeScanner ?: return
    val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
    // RSSI を受信のたびに知りたいので、一番短い間隔でスキャンする（同じデバイスの通知もまとめられない）
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    scanner.startScan(listOf(filter), settings, scanCallback)
    isScanning = true
  }

  @SuppressLint("MissingPermission")
  override fun stop() {
    if (!isScanning) return
    bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    isScanning = false
  }
}
