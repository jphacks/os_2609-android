package com.google.ar.core.examples.kotlin.helloar.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * Android の BLE スキャンで ESP32 のビーコンを受信する
 * 接続はせず、流れてくる広告パケットを受信するだけ
 * 権限（BLUETOOTH_SCAN など）は、start() を呼ぶ前に画面側でもらっておく
 */
class BleBeaconScanner(private val context: Context) : BeaconScanner {
  companion object {
    /** ESP32 側の SERVICE_UUID と同じ値。この UUID を広告しているデバイスだけを受信する */
    private val SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
  }

  override var onReceive: ((beaconName: String, rssi: Int) -> Unit)? = null

  private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
  /** start() から stop() までの間は true。Bluetooth がオフの間は、オンになるのを待っている */
  private var isRunning = false
  private var isScanning = false

  /** 広告パケットを受信するたびに呼ばれる。コールバックはメインスレッドで届く */
  private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      // device.name は BLUETOOTH_CONNECT の権限が要るので、広告パケットに入っている名前を使う
      val name = result.scanRecord?.deviceName ?: return
      onReceive?.invoke(name, result.rssi)
    }
  }

  /**
   * Bluetooth のオン・オフが変わったときに呼ばれる
   * スキャンは Bluetooth がオンでないと始められないので、オンになったら始め、オフになったら止まったことにする
   */
  private val stateReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
        BluetoothAdapter.STATE_ON -> startScan()
        BluetoothAdapter.STATE_OFF -> isScanning = false
      }
    }
  }

  override fun start() {
    if (isRunning) return
    isRunning = true
    // システムからの通知だけを受け取ればよいので、ほかのアプリには公開しない
    ContextCompat.registerReceiver(
      context,
      stateReceiver,
      IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    startScan()
  }

  @SuppressLint("MissingPermission")
  override fun stop() {
    if (!isRunning) return
    isRunning = false
    context.unregisterReceiver(stateReceiver)
    if (isScanning) {
      // Bluetooth がオフになっている途中だと例外が出ることがあるが、どのみちスキャンは止まっている
      runCatching { bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
      isScanning = false
    }
  }

  @SuppressLint("MissingPermission")
  private fun startScan() {
    if (isScanning) return
    // Bluetooth がオフのときは scanner が null になる。オンになったら stateReceiver からもう一度呼ばれる
    val scanner = bluetoothManager?.adapter?.bluetoothLeScanner ?: return
    val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
    // RSSI を受信のたびに知りたいので、一番短い間隔でスキャンする（同じデバイスの通知もまとめられない）
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    scanner.startScan(listOf(filter), settings, scanCallback)
    isScanning = true
  }
}
