package com.example.sunshine.ui.battle

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sunshine.model.Fighter
import com.example.sunshine.model.Location
import com.example.sunshine.model.Monster
import kotlin.math.roundToInt

/**
 * 対戦画面。同じ地点にいる相手とマッチングし、画面の空いている所をタップして攻撃し、溜まったら必殺技を撃つ
 * location は今いる地点。わからない間は null で、相手を探せない
 */
@Composable
fun BattleScreen(viewModel: BattleViewModel, location: Location?, modifier: Modifier = Modifier) {
    val me = viewModel.me
    val opponent = viewModel.opponent
    if (me != null && opponent != null) {
        FightContent(viewModel, me, opponent, modifier)
    } else {
        LobbyContent(
            myMonster = viewModel.myMonster,
            location = location,
            isSearching = viewModel.phase == BattleViewModel.Phase.Searching ||
                viewModel.phase == BattleViewModel.Phase.CancellingSearch,
            isCancelling = viewModel.phase == BattleViewModel.Phase.CancellingSearch,
            errorMessage = viewModel.errorMessage,
            onFind = { viewModel.findOpponent(it.id) },
            onCancel = viewModel::cancelSearch,
            modifier = modifier.padding(16.dp),
        )
    }
}

/** 対戦相手を探す前の画面。今いる地点と、自分のモンスターを見せる */
@Composable
private fun LobbyContent(
    myMonster: Monster,
    location: Location?,
    isSearching: Boolean,
    isCancelling: Boolean,
    errorMessage: String?,
    onFind: (Location) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("MONSTER BATTLE", style = MaterialTheme.typography.titleMedium)
        FighterImage(myMonster.name, MY_COLOR, 90)
        Text(myMonster.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "同じ場所にいる人と対戦できます。画面をタップして攻撃しよう",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (errorMessage != null) {
            Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.weight(1f))

        when {
            isSearching -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                if (isCancelling) {
                    Text("相手探しをやめています…")
                } else {
                    Text("${location?.name.orEmpty()}で相手を探しています…")
                    OutlinedButton(onClick = onCancel) { Text("やめる") }
                }
            }
            location != null -> Button(onClick = { onFind(location) }, modifier = Modifier.fillMaxWidth()) {
                Text("${location.name}で対戦相手を探す", style = MaterialTheme.typography.titleMedium)
            }
            else -> Text("現在地を探しています…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FightContent(viewModel: BattleViewModel, me: Fighter, opponent: Fighter, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 空いている所のタップで通常攻撃する。ボタンのタップはボタンが優先される
                .pointerInput(Unit) { detectTapGestures { viewModel.attack() } }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. 上部：相手の名前と HP
            StatBar(opponent.name, opponent.hp, opponent.maxHP, Color.Red)

            // 2. 中央上：相手のモンスター。倒したら薄くなる
            val opponentAlpha by animateFloatAsState(if (opponent.hp == 0) 0.2f else 1f, tween(500))
            HitFighter(
                name = opponent.name,
                color = OPPONENT_COLOR,
                size = 120,
                hit = viewModel.lastHitOnOpponent,
                damageColor = if (viewModel.lastHitOnOpponent?.isSpecial == true) Color(0xFFFFC107) else Color.Red,
                modifier = Modifier.align(Alignment.End).alpha(opponentAlpha),
            )

            Spacer(Modifier.weight(1f))

            // 3. 中央下：自分のモンスター
            HitFighter(
                name = me.name,
                color = MY_COLOR,
                size = 90,
                hit = viewModel.lastHitOnMe,
                damageColor = Color(0xFF9C27B0),
                modifier = Modifier.align(Alignment.Start),
            )

            // 4. 下部：自分の HP と必殺技ゲージ
            StatBar(me.name, me.hp, me.maxHP, Color(0xFF4CAF50))
            StatBar(
                "必殺技ゲージ",
                me.specialGauge,
                Fighter.SPECIAL_GAUGE_MAX,
                if (me.canUseSpecial) Color(0xFFFF9800) else Color(0xFFFFEB3B),
            )

            // 5. 最下部：必殺技ボタン
            Button(
                onClick = viewModel::useSpecial,
                enabled = me.canUseSpecial,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC107), contentColor = Color.Black),
            ) {
                Text(if (me.canUseSpecial) "⚡ 必殺技 READY!" else "⚡ 必殺技", style = MaterialTheme.typography.titleMedium)
            }
        }

        // 決着が確定したら結果を全面に出す
        viewModel.outcome?.let { outcome ->
            ResultOverlay(outcome, opponent.name, onBack = viewModel::leaveBattle)
        }
    }
}

/** モンスターと、攻撃が当たったときのダメージ表示と揺れ */
@Composable
private fun HitFighter(
    name: String,
    color: Color,
    size: Int,
    hit: BattleViewModel.Hit?,
    damageColor: Color,
    modifier: Modifier = Modifier,
) {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(hit?.id) {
        if (hit == null) return@LaunchedEffect
        for (x in listOf(-10f, 10f, -6f, 6f, 0f)) shake.animateTo(x, tween(40))
    }
    Box(modifier.offset { IntOffset(shake.value.roundToInt(), 0) }, contentAlignment = Alignment.Center) {
        FighterImage(name, color, size)
        if (hit != null) {
            // 当たるたびに作り直して、ダメージ表示を最初から流す
            key(hit.id) {
                FloatingDamage("-${hit.damage}", damageColor, if (hit.isSpecial) 64 else 36)
            }
        }
    }
}

/** ダメージの数字。上に浮かびながら消える */
@Composable
private fun FloatingDamage(text: String, color: Color, fontSize: Int) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(700)) }
    Text(
        text,
        color = color,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier
            .offset { IntOffset(0, (-60 * progress.value).dp.roundToPx()) }
            .alpha(1f - progress.value),
    )
}

/** モンスターの見た目の代わり。3D モデルを出せるようになるまでは、色付きの丸に名前の頭文字を出す */
@Composable
private fun FighterImage(name: String, color: Color, size: Int) {
    Box(
        Modifier
            .size(size.dp)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1), color = Color.White, fontSize = (size / 2).sp, fontWeight = FontWeight.Bold)
    }
}

/** 名前と数値付きの横棒（HP バー・必殺技ゲージ共通） */
@Composable
private fun StatBar(title: String, value: Int, maxValue: Int, color: Color) {
    val fraction by animateFloatAsState(value.toFloat() / maxValue, tween(200))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("$value / $maxValue", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(12.dp),
            color = color,
            drawStopIndicator = {},
        )
    }
}

/** 勝敗を全面に出し、ロビーに戻るボタンを置く。下の画面へのタップも遮る */
@Composable
private fun ResultOverlay(outcome: BattleViewModel.Outcome, opponentName: String, onBack: () -> Unit) {
    val won = outcome == BattleViewModel.Outcome.Won
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            // 下の画面にタップを通さない（決着後に攻撃しないように）
            .clickable(interactionSource = null, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(
                Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(if (won) "👑" else "✖", fontSize = 64.sp, color = if (won) Color(0xFFFFC107) else Color.Red)
                Text(if (won) "勝利！" else "敗北…", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(if (won) "${opponentName}に勝った！" else "${opponentName}に負けてしまった…")
                Button(onClick = onBack) { Text("ロビーに戻る") }
            }
        }
    }
}

private val MY_COLOR = Color(0xFF2196F3)
private val OPPONENT_COLOR = Color(0xFFFF9800)
