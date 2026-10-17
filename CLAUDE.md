# CLAUDE.md

iOS アプリ（[jphacks/os_2609-iOS](https://github.com/jphacks/os_2609-iOS)）と同じ Supabase のデータベースで対戦する Android アプリ（ハッカソン用）。

## 最初に読むもの

- バトルの仕様（**必ず全部読む**。この決まりを守らないと iOS と対戦できない）
  https://raw.githubusercontent.com/jphacks/os_2609-iOS/feature/supabase-connection/docs/battle.md
- テーブルと関数の定義
  https://raw.githubusercontent.com/jphacks/os_2609-iOS/feature/supabase-connection/supabase/schema.sql
- iOS の実装（迷ったらこちらに合わせる）
  - https://raw.githubusercontent.com/jphacks/os_2609-iOS/feature/supabase-connection/ARApp/Logic/BattleEngine.swift
  - https://raw.githubusercontent.com/jphacks/os_2609-iOS/feature/supabase-connection/ARApp/Services/SupabaseBattleService.swift
  - 同じディレクトリ構成で `Models/Battle/`・`Services/BattleService.swift`・`ViewModels/BattleViewModel.swift` もある

## プロジェクトの構成

- Kotlin + Jetpack Compose（Material 3）、1 モジュール（`:app`）、Activity は `MainActivity` だけ
- AGP 9.2（Kotlin は AGP に組み込み）、Kotlin 2.2.10、Gradle のバージョンカタログ（`gradle/libs.versions.toml`）
- minSdk 24 / targetSdk 36 / compileSdk 37（依存の androidx.core 1.19 が 37 を必要とする）、Java 11
- パッケージ名は `com.example.sunshine`

## 守ること

- データベース（テーブル・関数・RLS）は変えない。変える必要があれば、実装する前に相談する。schema.sql の正は iOS のリポジトリ。
- ルールの数値、地点の ID、モンスターの modelName、行動の文字列は、battle.md の表と完全に同じにする。
- Supabase には supabase-kt を使う。URL と anon key は `local.properties`（コミットしない。`.gitignore` 済み）に書く。service_role（secret）の鍵は使わない。
  ```properties
  supabase.url=https://xxxx.supabase.co
  supabase.anonKey=<anon（public）key>
  ```
- 現在地の推定はまだない。当面は地点 ID を `"entrance"`（入口）に固定する。そのコードには「⚠️ 開発用」のコメントを付け、現在地の推定ができたら置き換えることを書く。

## 進め方

- ハッカソン用。ユニットテストや UI テストは書かない。画面に動作確認用のボタンも置かない。
- 開発中だけ使うコード（ダミー実装など）は、ファイル先頭に「⚠️ 開発用」と書き、削除するときに一緒に消す場所も書く。
- 使われていないコードは残さず削除する。不要な抽象化はしない。
- コメントは日本語。iOS 側のコメントと同じくらいの密度で、「なぜそうするか」を書く。
- コミットメッセージは日本語で書く。
- Step ごとに進め、各 Step の終わりに、変更したファイルとその役割、動かして確かめる手順を説明する。

## アーキテクチャ

iOS と同じ分け方にし、ファイル名・型名もそろえる（iOS と見比べて直せるようにするため）。

```
app/src/main/java/com/example/sunshine/
├── MainActivity.kt              画面のルート。BattleService を選んで BattleScreen を出す
├── model/
│   ├── BattleAction.kt          enum。rawValue は "normalAttack" / "specialAttack"
│   ├── Fighter.kt               1 人ぶんの HP・ゲージ（data class）
│   ├── BattleState.kt           2 人の Fighter と winner（data class）
│   ├── Monster.kt               modelName と名前の一覧（battle.md の表と同じ）
│   └── Location.kt              地点 ID と名前の一覧（battle.md の表と同じ）
├── logic/
│   └── BattleEngine.kt          ルールの計算だけ。状態を受け取り、新しい状態とダメージを返す純粋な関数
├── service/
│   ├── BattleService.kt         相手とのやり取りの interface（iOS の BattleService と同じ 5 つの操作）
│   ├── DummyBattleService.kt    ⚠️ 開発用。CPU が 1 秒ごとに通常攻撃する
│   ├── SupabaseBattleService.kt battle.md の「Supabase とのやり取り」のとおりに通信する
│   └── SupabaseConfig.kt        local.properties → BuildConfig から URL と anon key を読む
└── ui/
    ├── theme/                   既存のまま
    └── battle/
        ├── BattleViewModel.kt   画面の状態（phase・state・outcome など）と進行。androidx の ViewModel
        └── BattleScreen.kt      ロビー / 相手探し / 対戦 / 決着 の Composable
```

- **画面（ui）**：Composable は ViewModel の状態を表示し、タップを ViewModel に伝えるだけ。ルールも通信も持たない。
- **状態（ViewModel + model + logic）**：
  - `BattleViewModel` が `phase`・`BattleState`・`outcome` を Compose の `mutableStateOf` で持つ。
  - 自分の行動も相手の行動も `BattleEngine.apply` を通す。クールダウン（0.3 秒）は ViewModel で、自分のタップだけに適用する。
  - 状態は自分を `"me"`、相手を `"opponent"` の ID で持つ（iOS と同じ）。
- **通信（service）**：
  - `BattleService` は `suspend fun findOpponent`・`fun opponentActions(): Flow<BattleAction>`・`fun send`・`suspend fun finish(iWon): Boolean`・`suspend fun leave()`。
  - Supabase の鍵が `local.properties` にないときは `DummyBattleService` を使う（鍵を持っていないメンバーでも画面を動かせるように。iOS と同じ）。
  - 通信は ViewModel の `viewModelScope` で動かし、画面を閉じたら止まるようにする。

### 追加する依存

- `io.github.jan-tennert.supabase`：`postgrest-kt`・`realtime-kt`（BOM でバージョンをそろえる）
- `io.ktor:ktor-client-okhttp`（supabase-kt が使う HTTP / WebSocket）
- `org.jetbrains.kotlin.plugin.serialization`（テーブルの行を `@Serializable` で読み書きする）
- `androidx.lifecycle:lifecycle-viewmodel-compose`
- `AndroidManifest.xml` に `INTERNET` 権限
