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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.ar.core.examples.kotlin.helloar.R
import com.google.ar.core.examples.kotlin.helloar.capture.CaptureActivity
import com.google.ar.core.examples.kotlin.helloar.model.Location
import com.google.ar.core.examples.kotlin.helloar.service.BeaconScanner
import com.google.ar.core.examples.kotlin.helloar.service.BleBeaconScanner
import com.google.ar.core.examples.kotlin.helloar.service.DummyBeaconScanner
import com.google.ar.core.examples.kotlin.helloar.viewmodel.LocationViewModel
import kotlinx.coroutines.launch

/**
 * マップ画面。屋内マップの上に、推定した現在地を表示する
 * アプリを開くと最初にこの画面が出る。ビーコンのスキャンは、この画面が見えている間だけ動かす
 */
class MapActivity : AppCompatActivity() {
  companion object {
    /** BLE のスキャンに要る権限。Android 12 から Bluetooth の権限が分かれた */
    private val REQUIRED_PERMISSIONS =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
      } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
      }

    /** エミュレータには Bluetooth がないので、ダミーのビーコンで登録済みの地点を巡ることにする */
    private fun isEmulator() = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
  }

  private val viewModel: LocationViewModel by viewModels {
    LocationViewModel.factory(makeScanner())
  }

  private val permissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      if (hasPermissions()) {
        viewModel.start()
      } else {
        Toast.makeText(this, "現在地を調べるには、付近のデバイスと位置情報の許可が必要です", Toast.LENGTH_LONG).show()
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_map)

    val statusText = findViewById<TextView>(R.id.status_text)
    val indoorMap = findViewById<IndoorMapView>(R.id.indoor_map)
    indoorMap.locations = Location.REGISTERED

    findViewById<Button>(R.id.capture_button).setOnClickListener {
      startActivity(Intent(this, CaptureActivity::class.java))
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
  }

  private fun hasPermissions() = REQUIRED_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
  }

  private fun makeScanner(): BeaconScanner =
    if (isEmulator()) {
      DummyBeaconScanner.walkingThrough(Location.REGISTERED)
    } else {
      BleBeaconScanner(applicationContext)
    }
}
