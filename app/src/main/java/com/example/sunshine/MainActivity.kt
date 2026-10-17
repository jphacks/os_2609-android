package com.example.sunshine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sunshine.model.Location
import com.example.sunshine.service.BattleService
import com.example.sunshine.service.DummyBattleService
import com.example.sunshine.service.SupabaseBattleService
import com.example.sunshine.service.SupabaseConfig
import com.example.sunshine.ui.battle.BattleScreen
import com.example.sunshine.ui.battle.BattleViewModel
import com.example.sunshine.ui.theme.SunshineTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SunshineTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BattleScreen(
                        viewModel = viewModel { BattleViewModel(battleService()) },
                        location = currentLocation,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    /** Supabase の鍵が local.properties にないときは、CPU が相手のダミーで動かす（鍵を持っていないメンバーでも画面を試せるように） */
    private fun battleService(): BattleService =
        if (SupabaseConfig.isConfigured) {
            SupabaseBattleService(SupabaseConfig.URL, SupabaseConfig.ANON_KEY)
        } else {
            DummyBattleService()
        }

    private companion object {
        /**
         * ⚠️ 開発用：この Android アプリには、まだ現在地の推定がないので、入口に固定する
         * 現在地の推定ができたら、推定した地点（わからない間は null）に置き換える
         */
        val currentLocation = Location.registered.first { it.id == "entrance" }
    }
}
