package com.google.ar.core.examples.kotlin.helloar.map

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.ar.core.examples.kotlin.helloar.ARAppApplication
import com.google.ar.core.examples.kotlin.helloar.R
import com.google.ar.core.examples.kotlin.helloar.battle.BattleActivity
import com.google.ar.core.examples.kotlin.helloar.capture.CaptureActivity
import com.google.ar.core.examples.kotlin.helloar.model.Location
import com.google.ar.core.examples.kotlin.helloar.service.BeaconScanner
import com.google.ar.core.examples.kotlin.helloar.service.BleBeaconScanner
import com.google.ar.core.examples.kotlin.helloar.service.DummyBeaconScanner
import com.google.ar.core.examples.kotlin.helloar.service.NearbyBattleDiscovery
import com.google.ar.core.examples.kotlin.helloar.starter.StarterMonsterActivity
import com.google.ar.core.examples.kotlin.helloar.viewmodel.LocationViewModel
import kotlinx.coroutines.launch

/**
 * マップ画面。屋内マップの上に、推定した現在地を表示する
 * アプリを開くと最初にこの画面が出る。ビーコンのスキャンは、この画面が見えている間だけ動かす
 * バトルに出すモンスターがまだ決まっていなければ、代わりに最初にモンスターを選ぶ画面（StarterMonsterActivity）を開く
 */
class MapActivity : AppCompatActivity() {
  companion object {
    /** BLE のスキャンに要る権限。Android 12 から Bluetooth の権限が分かれた */
    private val REQUIRED_PERMISSIONS =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
          Manifest.permission.BLUETOOTH_SCAN,
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        )
      } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
      }

    /** エミュレータには Bluetooth がないので、ダミーのビーコンで登録済みの地点を巡ることにする */
    private fun isEmulator() = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
  }

  private val viewModel: LocationViewModel by viewModels {
    LocationViewModel.factory(makeScanner())
  }

  private val nearby by lazy { NearbyBattleDiscovery(applicationContext, lifecycleScope) }
  private var candidateDialog: AlertDialog? = null
  private var shownCandidate: String? = null
  private var updatingReception = false
  private var receptionRequested = false
  private val battlePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    REQUIRED_PERMISSIONS + arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
  } else REQUIRED_PERMISSIONS
  private val battlePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    if (receptionRequested && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && battlePermissions.all(::hasPermission)) {
      nearby.start()
    } else {
      receptionRequested = false
      nearby.stop("対戦受付には付近のデバイスと正確な位置情報の許可が必要です")
    }
  }

  private val permissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      if (hasPermissions()) {
        viewModel.start()
      } else {
        // 「おおよその位置情報」だけを許可されたときも、ここに来る（ビーコンの受信には「正確な位置情報」が要る）
        Toast.makeText(this, "現在地を調べるには、付近のデバイスと「正確な位置情報」の許可が必要です", Toast.LENGTH_LONG).show()
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // この画面を閉じてから開くので、選ぶ画面で戻るとアプリが閉じる。権限のダイアログも選ぶ画面の上には出ない
    if ((application as ARAppApplication).monsterCollection.battleMonster == null) {
      startActivity(Intent(this, StarterMonsterActivity::class.java))
      finish()
      return
    }
    setContentView(R.layout.activity_map)
    // 背景が白いので、ステータスバーの時刻やアイコンを暗い色にする
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true

    val statusText = findViewById<TextView>(R.id.status_text)
    val indoorMap = findViewById<IndoorMapView>(R.id.indoor_map)
    indoorMap.locations = Location.REGISTERED

    findViewById<Button>(R.id.capture_button).setOnClickListener {
      startActivity(Intent(this, CaptureActivity::class.java))
    }
    val reception = findViewById<SwitchCompat>(R.id.battle_reception)
    reception.setOnCheckedChangeListener { _, accepting ->
      if (!updatingReception) {
        receptionRequested = accepting
        if (!accepting) nearby.stop()
        else if (battlePermissions.all(::hasPermission)) nearby.start()
        else battlePermissionLauncher.launch(battlePermissions)
      }
    }
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        nearby.state.collect { state ->
          updatingReception = true
          reception.isChecked = state.accepting
          updatingReception = false
          findViewById<TextView>(R.id.battle_status).text = state.message
          if (state.candidate != shownCandidate) {
            candidateDialog?.dismiss()
            candidateDialog = null
            shownCandidate = state.candidate
            if (state.candidate != null) {
              candidateDialog = AlertDialog.Builder(this@MapActivity)
                .setTitle("近くに対戦相手がいます")
                .setMessage("お互いに対戦受付中であることを確認しました。対戦を開始しますか？")
                .setPositiveButton("対戦する") { _, _ -> nearby.confirmCandidate() }
                .setNegativeButton("今回はやめる") { _, _ -> nearby.dismissCandidate() }
                .setCancelable(false)
                .show()
            }
          }
          state.readyToken?.let { token ->
            nearby.consumeReadyToken()
            startActivity(Intent(this@MapActivity, BattleActivity::class.java).putExtra(BattleActivity.PAIRING_TOKEN, token))
          }
        }
      }
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.state.collect { state ->
          statusText.text = state.statusMessage
          indoorMap.currentLocation = state.currentLocation
          indoorMap.currentPosition = state.currentPosition
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    if (isEmulator() || hasPermissions()) {
      viewModel.start()
    } else {
      permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }
  }

  override fun onStop() {
    super.onStop()
    viewModel.stop()
    receptionRequested = false
    nearby.stop()
    candidateDialog?.dismiss()
    candidateDialog = null
    shownCandidate = null
  }

  private fun hasPermission(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

  private fun hasPermissions() = REQUIRED_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
  }

  private fun makeScanner(): BeaconScanner =
    if (isEmulator()) {
      DummyBeaconScanner(Location.REGISTERED)
    } else {
      BleBeaconScanner(applicationContext)
    }
}
