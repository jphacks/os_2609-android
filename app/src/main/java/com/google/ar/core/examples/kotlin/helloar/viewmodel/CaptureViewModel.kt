package com.google.ar.core.examples.kotlin.helloar.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.ar.core.examples.kotlin.helloar.model.Monster
import com.google.ar.core.examples.kotlin.helloar.model.WildMonster
import com.google.ar.core.examples.kotlin.helloar.random
import com.google.ar.core.examples.kotlin.helloar.service.MonsterCollection
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** AR 捕獲画面の状態 */
data class CaptureState(
  /** モンスターを置く床が見つかったか */
  val isFloorFound: Boolean = false,
  /** 「現れた！」などのお知らせ。表示し終わったら null に戻る */
  val notice: String? = null,
  /** 詳細画面に出している、捕獲したモンスター。詳細画面を開いていなければ null */
  val detailMonster: Monster? = null,
  /**
   * 出現しているモンスター。一度に出るのは 1 体まで
   * CaptureArSceneController はこれに合わせて 3D のモンスターを置いたり消したりする。床が見つかり直したら、その近くに置き直す
   */
  val wildMonster: WildMonster? = null,
) {
  val statusMessage: String
    get() = when {
      notice != null -> notice
      !isFloorFound -> "床を映して、ゆっくり動かしてください"
      wildMonster == null -> "歩き回ってモンスターを探そう"
      else -> "モンスターをタップして捕まえよう"
    }
}

/**
 * AR 捕獲画面の状態とルール。ARCore や SceneView には触らず、状態だけを持つ（iOS の ARCaptureViewModel）
 * 歩いた距離に応じてモンスターを出し、3D での配置や動きは CaptureArSceneController が担当する
 * モンスターをタップすると捕獲成功として保存し、詳細画面を開く
 * （iOS はタップでバトルになり、勝つと捕獲になる。Android にバトルを移植したら、monsterTapped でバトルを始める）
 * モンスターはこの端末の中だけに出る（ほかのプレイヤーとは共有しない）
 */
class CaptureViewModel(private val collection: MonsterCollection) : ViewModel() {
  companion object {
    /** 「現れた！」などのお知らせを表示しておく時間（ミリ秒） */
    private const val NOTICE_DURATION_MS = 2_000L
    /** モンスターが出るまでに歩く距離（m）。出るたびにこの範囲から選び直す */
    private val SPAWN_WALK_DISTANCE_RANGE = 1f..3f
    /** 出現してから逃げるまでの時間（ミリ秒）。床が見えていない間（画面を離れている間も）は数えず、見つかり直したら数え直す */
    private const val ESCAPE_DURATION_MS = 60_000L

    fun factory(collection: MonsterCollection): ViewModelProvider.Factory = viewModelFactory {
      initializer { CaptureViewModel(collection) }
    }
  }

  private val _state = MutableStateFlow(CaptureState())
  val state: StateFlow<CaptureState> = _state.asStateFlow()

  /** 次のモンスターが出るまでに、あと何 m 歩くか。画面には出さないので state に入れない */
  private var remainingWalkDistance = SPAWN_WALK_DISTANCE_RANGE.random()
  private var noticeJob: Job? = null
  private var escapeJob: Job? = null

  /** 床のアンカーを置いた（または外れた）ときに CaptureArSceneController から呼ばれる */
  fun floorStateChanged(isFound: Boolean) {
    _state.update { it.copy(isFloorFound = isFound) }
    if (isFound) {
      if (_state.value.wildMonster != null) {
        startEscapeTimer()
      }
    } else {
      noticeJob?.cancel()
      noticeJob = null
      _state.update { it.copy(notice = null) }
      // 見えていないうちに逃げると、戻ってきたときに知らせが出ず、いなくなった理由がわからない
      escapeJob?.cancel()
      escapeJob = null
    }
  }

  /**
   * 自分が床の上を歩いたときに CaptureArSceneController から呼ばれる。distance は歩いた距離（m）
   * 決まった距離を歩くと、モンスターが出る。出ている間と、詳細画面を出している間は数えない
   */
  fun walked(distance: Float) {
    val current = _state.value
    if (!current.isFloorFound || current.wildMonster != null || current.detailMonster != null) return
    remainingWalkDistance -= distance
    if (remainingWalkDistance > 0) return

    remainingWalkDistance = SPAWN_WALK_DISTANCE_RANGE.random()
    val wild = WildMonster(Monster.ALL.random())
    _state.update { it.copy(wildMonster = wild) }
    showNotice("${wild.monster.name}が現れた！")
    startEscapeTimer()
  }

  /** モンスターがタップされたときに CaptureArSceneController から呼ばれる。離れていても捕まえられる */
  fun monsterTapped(id: UUID) {
    val target = _state.value.wildMonster ?: return
    if (target.id != id) return
    escapeJob?.cancel()
    escapeJob = null
    collection.add(target.monster)
    _state.update { it.copy(wildMonster = null, detailMonster = target.monster) }
  }

  /** 詳細画面を閉じて AR に戻る。また歩くと次のモンスターが出る */
  fun closeDetail() {
    _state.update { it.copy(detailMonster = null) }
  }

  /** 出現しているモンスターが、ESCAPE_DURATION_MS たつと逃げるようにする */
  private fun startEscapeTimer() {
    val wild = _state.value.wildMonster ?: return
    escapeJob?.cancel()
    escapeJob = viewModelScope.launch {
      delay(ESCAPE_DURATION_MS)
      if (_state.value.wildMonster?.id != wild.id) return@launch
      _state.update { it.copy(wildMonster = null) }
      escapeJob = null
      showNotice("${wild.monster.name}は逃げてしまった…")
    }
  }

  private fun showNotice(message: String) {
    // 続けて表示するときは、前の表示を消すタイマーを止めて新しいお知らせに変える
    noticeJob?.cancel()
    _state.update { it.copy(notice = message) }
    noticeJob = viewModelScope.launch {
      delay(NOTICE_DURATION_MS)
      _state.update { it.copy(notice = null) }
    }
  }
}
