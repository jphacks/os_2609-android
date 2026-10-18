package com.google.ar.core.examples.kotlin.helloar.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.ar.core.examples.kotlin.helloar.model.Monster
import com.google.ar.core.examples.kotlin.helloar.service.MonsterCollection

/**
 * 最初にモンスターを選ぶ画面の状態（iOS の StarterMonsterViewModel）
 * 手前に出ているモンスターが選ばれた状態で、決めたモンスターは捕まえたものとして保存し、バトルに出すモンスターにもする
 */
class StarterMonsterViewModel(private val collection: MonsterCollection) : ViewModel() {
  companion object {
    /**
     * 最初に選べるモンスター（バニー、フロッグ、バード）。iOS と同じ
     * どれも Monster.ALL にあるので、candidates は空にならない
     */
    private val CANDIDATE_MODEL_NAMES = listOf("monster-bunny", "monster-frog", "monster-birb")

    fun factory(collection: MonsterCollection): ViewModelProvider.Factory = viewModelFactory {
      initializer { StarterMonsterViewModel(collection) }
    }
  }

  val candidates = CANDIDATE_MODEL_NAMES.mapNotNull(Monster::named)

  /** 手前に出ている（選ばれている）モンスターの番号 */
  var selectedIndex by mutableIntStateOf(0)
    private set

  val selected: Monster
    get() = candidates[selectedIndex]

  /** 次のモンスターを手前に出す。最後まで行ったら最初に戻る */
  fun showNext() {
    selectedIndex = (selectedIndex + 1) % candidates.size
  }

  /** 前のモンスターを手前に出す。最初より前は最後に戻る */
  fun showPrevious() {
    selectedIndex = (selectedIndex - 1 + candidates.size) % candidates.size
  }

  /** 手前に出ているモンスターに決める */
  fun confirm() {
    collection.add(selected)
    collection.setBattleMonster(selected)
  }
}
