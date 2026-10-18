package com.google.ar.core.examples.kotlin.helloar.capture

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper
import com.google.ar.core.examples.java.common.helpers.TapHelper
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.kotlin.common.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.examples.kotlin.helloar.ARAppApplication
import com.google.ar.core.examples.kotlin.helloar.R
import com.google.ar.core.examples.kotlin.helloar.viewmodel.CaptureViewModel
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * AR 捕獲画面（iOS の ARCaptureView）。カメラ映像の上に状態メッセージを重ねる
 * 歩き回るとモンスターが出て、AR で周りを歩く。タップすると捕まえて、捕獲成功の画面を重ねて出す
 * ARCore のセッションは、この画面が表示されている間だけ動かす（ARCoreSessionLifecycleHelper が onResume / onPause で開始・停止する）
 */
class CaptureActivity : AppCompatActivity() {
  companion object {
    private const val TAG = "CaptureActivity"
  }

  private val viewModel: CaptureViewModel by viewModels {
    CaptureViewModel.factory((application as ARAppApplication).monsterCollection)
  }

  lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
    private set
  private val snackbarHelper = SnackbarHelper()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_capture)

    val surfaceView = findViewById<GLSurfaceView>(R.id.surface_view)
    val arrowView = findViewById<MonsterArrowView>(R.id.arrow_view)
    val tapHelper = TapHelper(this).also { surfaceView.setOnTouchListener(it) }

    arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
    // セッションを作れない・開始できないときは、理由を出す
    arCoreSessionHelper.exceptionCallback = { exception ->
      val message = when (exception) {
        is UnavailableUserDeclinedInstallationException -> "Google Play 開発者サービス（AR）をインストールしてください"
        is UnavailableApkTooOldException -> "ARCore を更新してください"
        is UnavailableSdkTooOldException -> "アプリを更新してください"
        is UnavailableDeviceNotCompatibleException -> "この端末は AR に対応していません"
        is CameraNotAvailableException -> "カメラを使えません。アプリを開き直してください"
        else -> "AR を開始できませんでした：$exception"
      }
      Log.e(TAG, "ARCore threw an exception", exception)
      showError(message)
    }
    arCoreSessionHelper.beforeSessionResume = ::configureSession
    // 登録した順に呼ばれる。セッションを開始してから、描画を始める
    lifecycle.addObserver(arCoreSessionHelper)
    val renderer = CaptureRenderer(this, viewModel, tapHelper, arrowView)
    lifecycle.addObserver(renderer)
    lifecycle.addObserver(object : DefaultLifecycleObserver {
      override fun onResume(owner: LifecycleOwner) = surfaceView.onResume()
      override fun onPause(owner: LifecycleOwner) = surfaceView.onPause()
    })
    SampleRender(surfaceView, renderer, assets)

    bindState()
  }

  /** ViewModel の状態を画面に出す */
  private fun bindState() {
    val statusText = findViewById<TextView>(R.id.status_text)
    val detailOverlay = findViewById<View>(R.id.detail_overlay)
    val detailName = findViewById<TextView>(R.id.detail_name)
    val detailSize = findViewById<TextView>(R.id.detail_size)
    findViewById<Button>(R.id.detail_close).setOnClickListener { viewModel.closeDetail() }

    // 画面に戻ってきたときに、同じモンスターでもう一度振動しないよう、collect の外で覚えておく
    var lastWildId: UUID? = null
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.state.collect { state ->
          statusText.text = state.statusMessage

          // モンスターが現れたら振動させ、画面を見ていなくても気づけるようにする
          val wildId = state.wildMonster?.id
          if (wildId != null && wildId != lastWildId) {
            statusText.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
          }
          lastWildId = wildId

          val detail = state.detailMonster
          detailOverlay.visibility = if (detail != null) View.VISIBLE else View.GONE
          if (detail != null) {
            detailName.text = detail.name
            detailSize.text = getString(R.string.capture_size, detail.size)
          }
        }
      }
    }
  }

  /** 床にモンスターを置くので水平面を探し、モンスターを部屋の明るさで照らせるよう、明るさを推定する */
  private fun configureSession(session: Session) {
    session.configure(
      session.config.apply {
        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
        lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
      }
    )
  }

  /** GL スレッドからも呼ばれる（SnackbarHelper がメインスレッドで出す） */
  fun showError(message: String) = snackbarHelper.showError(this, message)

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, results)
    arCoreSessionHelper.onRequestPermissionsResult(requestCode, permissions, results)
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
  }
}
