package com.google.ar.core.examples.kotlin.helloar.starter

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.examples.kotlin.helloar.battle.MonsterModelComponent
import com.google.ar.core.examples.kotlin.helloar.viewmodel.StarterMonsterViewModel

/** これだけ横にスワイプしたら、次（前）のモンスターを出す */
private val SWIPE_THRESHOLD = 50.dp

/**
 * 最初にモンスターを選ぶ画面（iOS の StarterMonsterView）。横にスワイプ（または ‹ ›）で切り替えて、手前のモンスターに決める
 * iOS は 3 体を輪にして回すが、こちらは MonsterModelComponent で 1 体ずつ出す
 */
@Composable
fun StarterMonsterScreen(viewModel: StarterMonsterViewModel, onDecided: () -> Unit) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp),
    modifier = Modifier
      .fillMaxSize()
      .background(Color.White)
      .safeDrawingPadding()
      .padding(16.dp),
  ) {
    Text("最初のモンスターを選ぼう", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.Black)

    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
      MonsterModelComponent(viewModel.selected, facingDegrees = 0f, modifier = Modifier.fillMaxSize())
      // モデルの上に重ねてスワイプを受け取る。重ねないと、スワイプがモデルを指で回す操作になってしまう
      Box(
        modifier = Modifier
          .fillMaxSize()
          .pointerInput(Unit) {
            var dragged = 0f
            detectHorizontalDragGestures(
              onDragStart = { dragged = 0f },
              onDragEnd = {
                // 左にスワイプすると次の、右にスワイプすると前のモンスターが出る（iOS と同じ）
                val threshold = SWIPE_THRESHOLD.toPx()
                if (dragged < -threshold) {
                  viewModel.showNext()
                } else if (dragged > threshold) {
                  viewModel.showPrevious()
                }
              },
            ) { _, amount -> dragged += amount }
          },
      )
      ArrowButtonComponent("‹", onClick = viewModel::showPrevious, modifier = Modifier.align(Alignment.CenterStart))
      ArrowButtonComponent("›", onClick = viewModel::showNext, modifier = Modifier.align(Alignment.CenterEnd))
    }

    Text(viewModel.selected.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black)

    // 何番目のモンスターが出ているかの目印
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      viewModel.candidates.indices.forEach { index ->
        val color = if (index == viewModel.selectedIndex) Color.Black else Color.Gray.copy(alpha = 0.3f)
        Box(Modifier.size(8.dp).background(color, CircleShape))
      }
    }

    Button(
      onClick = {
        viewModel.confirm()
        onDecided()
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("このモンスターにする", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
    }
  }
}

@Composable
private fun ArrowButtonComponent(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  FilledTonalIconButton(onClick = onClick, modifier = modifier) {
    Text(text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
  }
}
