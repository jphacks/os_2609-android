# iOS と共通の近接対戦

マップ画面の「対戦受付」をオンにすると、近くで受付中の iOS / Android を探す。ペアが確保されると確認画面を出し、双方が「対戦する」を選んだ場合だけオンライン対戦を始める。
野生モンスターとのバトルは従来どおり `CPUBattleService(opponent)` を使い、勝ったら捕獲して端末に保存する。

## DB と API

正は iOS リポジトリの `supabase/schema.sql` と `NearbyBattleDiscovery.swift`。
既存の共通 Supabase には次の iOS 移行を適用しておく（この Android 修正で DB を追加変更する必要はない）。

1. `20261018_nearby_battle_pairing.sql`
2. `20261018_nearby_battle_consent.sql`

- `nearby_pair(p_session, p_action, p_peer?, p_token?)`：`poll` / `offer` / `accept` / `stop`。受付ごとに作る UUID を `p_session` として送り、500ms 間隔で状態を確認する。
- `pending`：同じペアの確認画面を双方に出す。片方だけの承諾では対戦を開始しない。
- `ready`：サーバー発行のペア ID を `p_pairing_token` として `join_battle(p_player_id, p_monster, p_pairing_token)` に渡す。
- 辞退・受付OFF・マップからの移動・バックグラウンド移行：`stop` で保留中のペアをキャンセルする。
- 無応答・強制終了：ペア確保から30秒で期限切れ。双方承諾後の対戦相手待ちにも30秒の期限がある。
- `battle_actions`・`keep_waiting`・`finish_battle` と攻撃の文字列・ダメージ計算は共通仕様を維持する。
- 旧2引数の `join_battle` は呼ばない。端末ごとに別のランダムトークンを渡すだけでは、相手と一致せず対戦できない。

`local.properties` の Supabase URL / anon key が必要。未設定や認証失敗時はエラーを表示して受付を終了する。
古い Android アプリは新DBのマッチングAPIに対応していないため、この変更を含むAPKへ更新する。

## BLE の共通仕様

| 項目 | 値 |
|---|---|
| サービス UUID | `7D6A7624-49E5-4A5B-A250-268076174520` |
| 書き込み・通知の Characteristic UUID | `DDF82268-6904-4CE5-9F1E-C59CB09E7BC4` |
| 通知購読の CCCD | `00002902-0000-1000-8000-00805f9b34fb` |
| 値 | 受付ごとの UUID を UTF-8 の36バイトで送る |

Android も広告とスキャンの両方を行い、GATT サーバーとクライアントを持つ。最初に750ms候補を集めて RSSI の強い端末に1台ずつ接続し、接続が10秒進まなければ次を試す。ペアの競合はサーバーが排他制御する。
クライアントは MTU を要求して UUID が通知1回に収まることを確認し、暗号化が必要な通知購読の後でセッション ID を書き込む。サーバーは自分のセッション ID を通知で返す。
Android 12以降は `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE` を、ビーコンの位置推定用に正確な位置情報も許可する。システムのペアリング確認が表示されたら承認する。
BLE 広告が非対応の端末では受付を開始せず、その旨を表示する。エミュレーターでは実機間の近接対戦を確認できない。
実装は [Android GATT コールバックの仕様](https://developer.android.com/reference/android/bluetooth/BluetoothGattServerCallback) に従う。

## ビルドと実機確認

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

1. 共通DBへ接続する最新 iOS と Android を近くに置く。両方のマップで受付をオンにする。
2. 確認画面が双方に出ること、片方だけの承諾では開始せず、双方承諾で同じ対戦へ入ることを確かめる。
3. Android 同士でも同じ操作を行う。
4. 片方の辞退・受付OFF・画面移動・30秒の無応答で、相手が待ち続けないことを確かめる。
5. 3台以上を同時に受付し、候補が食い違ったまま待たないことを確かめる。
6. 野生CPU対戦・勝利時の捕獲保存・「にげる」が従来どおり動くことを確かめる。
