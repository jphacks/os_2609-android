package com.google.ar.core.examples.kotlin.helloar.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * iOS と同じ GATT サービスで受付中の端末を発見する。BLE では受付ごとの ID だけを交換する。
 * ペアの排他確保・双方承諾・辞退・30秒の期限は、共通の nearby_pair RPC で管理する。
 * 権限を得た後、マップが前面にある間だけ start() する。状態と BLE 操作はメインスレッドに揃える。
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // API 24〜32 でも同じ GATT の送受信を使う
class NearbyBattleDiscovery(context: Context, private val scope: CoroutineScope) {
  companion object {
    val SERVICE_UUID: UUID = UUID.fromString("7D6A7624-49E5-4A5B-A250-268076174520")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("DDF82268-6904-4CE5-9F1E-C59CB09E7BC4")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
  }

  data class State(
    val accepting: Boolean = false,
    val candidate: String? = null,
    val waitingForConsent: Boolean = false,
    val readyToken: String? = null,
    val message: String = "受付をオンにすると近くの相手を探します",
  )

  private val context = context.applicationContext
  private val manager = context.getSystemService(BluetoothManager::class.java)
  private val handler = Handler(Looper.getMainLooper())
  private val mutableState = MutableStateFlow(State())
  val state = mutableState.asStateFlow()
  private var session = ""
  private var reservedToken: String? = null
  private var wantsToAccept = false
  private var pairingJob: Job? = null
  private var selectionJob: Job? = null
  private var connectionJob: Job? = null
  private var server: BluetoothGattServer? = null
  private var characteristic: BluetoothGattCharacteristic? = null
  private var activeGatt: BluetoothGatt? = null
  private var scanCallback: ScanCallback? = null
  private var advertiseCallback: AdvertiseCallback? = null
  private val discovered = mutableMapOf<String, ScanResult>()
  private val remoteSessions = linkedSetOf<String>()
  private val subscribed = mutableSetOf<String>()
  private val preparedWrites = mutableMapOf<String, ByteArray>()
  private val notifications = ArrayDeque<BluetoothDevice>()
  private var notifying = false

  fun start() {
    if (state.value.accepting) return
    if (!SupabaseConfig.isConfigured) { stop("対戦の接続設定がありません"); return }
    val adapter = manager?.adapter
    if (adapter?.isEnabled != true) { stop("Bluetoothをオンにしてください"); return }
    if (adapter.bluetoothLeAdvertiser == null) { stop("この端末は対戦受付の広告に対応していません"); return }
    session = UUID.randomUUID().toString()
    reservedToken = null
    wantsToAccept = false
    remoteSessions.clear()
    mutableState.value = State(accepting = true, message = "近くで対戦受付中の相手を探しています")
    val generation = session
    try {
      val handshake = BluetoothGattCharacteristic(
        CHARACTERISTIC_UUID,
        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
      ).apply {
        addDescriptor(BluetoothGattDescriptor(CCCD_UUID,
          BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED))
      }
      characteristic = handshake
      server = manager.openGattServer(context, serverCallback(generation))
      val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(handshake)
      }
      check(server?.addService(service) == true)
      startPairing(generation)
    } catch (e: Exception) {
      stop("Bluetoothの対戦受付を開始できませんでした")
    }
  }

  fun stop(message: String = "受付をオンにすると近くの相手を探します") {
    mutableState.value = State(message = message)
    pairingJob?.cancel()
    pairingJob = null
    stopTransport()
  }

  fun confirmCandidate() {
    if (state.value.candidate == null) return
    wantsToAccept = true
    mutableState.value = state.value.copy(candidate = null, waitingForConsent = true,
      message = "相手の承諾を待っています（最大30秒）")
  }

  fun dismissCandidate() = stop("対戦を辞退しました。受付をオンにすると再び探します")
  fun consumeReadyToken() { mutableState.value = state.value.copy(readyToken = null) }

  private fun startPairing(generation: String) {
    pairingJob = scope.launch {
      val client = try {
        createSupabaseClient(SupabaseConfig.url, SupabaseConfig.anonKey) { install(Postgrest) }
      } catch (e: Exception) {
        stop("対戦の接続設定を確認してください")
        return@launch
      }
      suspend fun request(action: String, peer: String? = null, token: String? = null): List<PairRow> =
        withTimeout(10_000) {
          client.postgrest.rpc("nearby_pair", buildJsonObject {
            put("p_session", generation)
            put("p_action", action)
            peer?.let { put("p_peer", it) }
            token?.let { put("p_token", it) }
          }).decodeAs<List<PairRow>>()
        }
      try {
        while (isActive && state.value.accepting && session == generation) {
          check(manager?.adapter?.isEnabled == true)
          flushNotifications()
          val peer = if (reservedToken == null) remoteSessions.firstOrNull() else null
          val action = if (wantsToAccept) "accept" else if (peer == null) "poll" else "offer"
          // 先頭の候補が他の相手と確保済みでも、次の候補へ進める。
          if (peer != null) remoteSessions.remove(peer)
          val token = reservedToken
          // キャンセルで予約の応答を失わず、必ずこの通信の後に stop を送る。
          val row = withContext(NonCancellable) { request(action, peer, token) }.firstOrNull()
          if (!isActive || !state.value.accepting || session != generation) return@launch
          if (row == null && peer != null) remoteSessions.add(peer)
          if (row != null) {
            reservedToken = row.id
            stopTransport()
            when (row.status) {
              "pending" -> if (!wantsToAccept) mutableState.value = state.value.copy(candidate = row.id)
              "ready" -> {
                stop()
                mutableState.value = state.value.copy(readyToken = row.id)
                return@launch
              }
              "expired" -> { stop("確認の時間が切れました。受付をオンにして再度お試しください"); return@launch }
              else -> { stop("相手が対戦を辞退したか、受付を終了しました"); return@launch }
            }
          }
          delay(500)
        }
      } catch (e: TimeoutCancellationException) {
        if (session == generation && state.value.accepting) stop("対戦相手との通信が時間切れになりました")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        if (session == generation && state.value.accepting) stop("対戦相手との確認に失敗しました。接続設定と通信を確認してください")
      } finally {
        withContext(NonCancellable) {
          runCatching { request("stop") }
          client.close()
        }
      }
    }
  }

  /** 停止済み・確保済みの受付へ、遅れて届いた BLE コールバックを適用しない。 */
  private fun post(generation: String, action: () -> Unit) {
    handler.post {
      if (state.value.accepting && session == generation && reservedToken == null) {
        try { action() } catch (e: Exception) { stop("Bluetoothの通信に失敗しました") }
      }
    }
  }

  private fun receive(value: ByteArray) {
    if (value.size != 36) return
    val peer = runCatching { UUID.fromString(value.toString(Charsets.UTF_8)).toString() }.getOrNull() ?: return
    if (peer != session) remoteSessions.add(peer)
  }

  private fun serverCallback(generation: String) = object : BluetoothGattServerCallback() {
    override fun onServiceAdded(status: Int, service: BluetoothGattService) = post(generation) {
      if (status != BluetoothGatt.GATT_SUCCESS) stop("Bluetoothの対戦受付を開始できませんでした")
      else startAdvertisingAndScanning(generation)
    }

    override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) = post(generation) {
      val value = if (device.address in subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
      server?.sendResponse(device, requestId, if (offset == 0) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET, offset, value)
    }

    override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) = post(generation) {
      val valid = descriptor.uuid == CCCD_UUID && !preparedWrite && offset == 0 &&
        (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) || value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE))
      if (valid) {
        if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) subscribed.add(device.address)
        else subscribed.remove(device.address)
      }
      if (responseNeeded) server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
    }

    override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) = post(generation) {
      val prefix = if (preparedWrite) preparedWrites[device.address] ?: byteArrayOf() else byteArrayOf()
      val combined = prefix + value
      val valid = characteristic.uuid == CHARACTERISTIC_UUID && device.address in subscribed &&
        offset == prefix.size && combined.size <= 36 && (preparedWrite || combined.size == 36)
      if (valid && preparedWrite) preparedWrites[device.address] = combined
      if (responseNeeded) server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, if (preparedWrite) value else null)
      if (valid && !preparedWrite) reply(device, combined)
    }

    override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) = post(generation) {
      val value = preparedWrites.remove(device.address)
      val valid = !execute || value?.size == 36
      server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, 0, null)
      if (execute && valid && value != null) reply(device, value)
    }

    override fun onNotificationSent(device: BluetoothDevice, status: Int) = post(generation) {
      notifying = false
      flushNotifications()
    }

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = post(generation) {
      if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        subscribed.remove(device.address)
        preparedWrites.remove(device.address)
      }
    }
  }

  private fun reply(device: BluetoothDevice, value: ByteArray) {
    receive(value)
    notifications.addLast(device)
    flushNotifications()
  }

  private fun flushNotifications() {
    if (notifying) return
    val device = notifications.firstOrNull() ?: return
    val handshake = characteristic ?: return
    handshake.value = session.toByteArray(Charsets.UTF_8)
    if (server?.notifyCharacteristicChanged(device, handshake, false) == true) {
      notifications.removeFirst()
      notifying = true
    }
  }

  private fun startAdvertisingAndScanning(generation: String) {
    val callback = object : AdvertiseCallback() {
      override fun onStartFailure(errorCode: Int) = post(generation) { stop("この端末で対戦受付の広告を開始できませんでした") }
    }
    advertiseCallback = callback
    manager?.adapter?.bluetoothLeAdvertiser?.startAdvertising(
      AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).setTimeout(0).build(),
      AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(SERVICE_UUID)).build(), callback,
    )
    val scanner = object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult) = post(generation) {
        discovered[result.device.address] = result
        scheduleSelection()
      }
      override fun onScanFailed(errorCode: Int) = post(generation) { stop("近くの相手を検索できませんでした") }
    }
    scanCallback = scanner
    manager?.adapter?.bluetoothLeScanner?.startScan(
      listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
      ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanner,
    )
  }

  private fun scheduleSelection() {
    if (activeGatt != null || selectionJob != null || !state.value.accepting || reservedToken != null) return
    selectionJob = scope.launch {
      delay(750)
      selectionJob = null
      if (!state.value.accepting || reservedToken != null || activeGatt != null) return@launch
      val next = discovered.values.maxByOrNull { it.rssi } ?: return@launch
      discovered.remove(next.device.address)
      val generation = session
      try {
        activeGatt = next.device.connectGatt(context, false, clientCallback(generation), BluetoothDevice.TRANSPORT_LE)
        val connecting = activeGatt
        connectionJob = scope.launch {
          delay(10_000)
          if (activeGatt === connecting) connecting?.let(::releaseGatt)
        }
      } catch (e: Exception) { stop("Bluetoothの接続に失敗しました") }
    }
  }

  private fun clientCallback(generation: String) = object : BluetoothGattCallback() {
    private fun current(gatt: BluetoothGatt, action: () -> Unit) = post(generation) {
      if (activeGatt === gatt) action()
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = current(gatt) {
      if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) releaseGatt(gatt)
      else if (newState == BluetoothProfile.STATE_CONNECTED && !gatt.requestMtu(185)) releaseGatt(gatt)
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = current(gatt) {
      // UUID の文字列36バイトが通知1回に収まるサイズを確保してから交換する。
      if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 39) gatt.discoverServices() else releaseGatt(gatt)
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = current(gatt) {
      val handshake = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
      val descriptor = handshake?.getDescriptor(CCCD_UUID)
      if (status != BluetoothGatt.GATT_SUCCESS || handshake == null || descriptor == null || !gatt.setCharacteristicNotification(handshake, true)) {
        releaseGatt(gatt)
      } else {
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!gatt.writeDescriptor(descriptor)) releaseGatt(gatt)
      }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = current(gatt) {
      val handshake = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
      if (descriptor.uuid != CCCD_UUID || status != BluetoothGatt.GATT_SUCCESS || handshake == null) releaseGatt(gatt)
      else {
        handshake.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        handshake.value = generation.toByteArray(Charsets.UTF_8)
        if (!gatt.writeCharacteristic(handshake)) releaseGatt(gatt)
      }
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = current(gatt) {
      if (status != BluetoothGatt.GATT_SUCCESS) releaseGatt(gatt)
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
      val value = characteristic.value?.copyOf() ?: return
      current(gatt) {
        if (characteristic.uuid == CHARACTERISTIC_UUID) { receive(value); releaseGatt(gatt) }
      }
    }
  }

  private fun releaseGatt(gatt: BluetoothGatt) {
    if (activeGatt !== gatt) return
    activeGatt = null
    connectionJob?.cancel()
    connectionJob = null
    gatt.disconnect()
    gatt.close()
    scheduleSelection()
  }

  private fun stopTransport() {
    selectionJob?.cancel()
    selectionJob = null
    connectionJob?.cancel()
    connectionJob = null
    val gatt = activeGatt
    activeGatt = null
    runCatching { gatt?.disconnect(); gatt?.close() }
    runCatching { scanCallback?.let { manager?.adapter?.bluetoothLeScanner?.stopScan(it) } }
    runCatching { advertiseCallback?.let { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
    scanCallback = null
    advertiseCallback = null
    runCatching { server?.close() }
    server = null
    characteristic = null
    discovered.clear()
    subscribed.clear()
    preparedWrites.clear()
    notifications.clear()
    notifying = false
  }
}

@Serializable
private data class PairRow(val id: String, val status: String)
