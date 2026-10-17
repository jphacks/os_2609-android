# iOS と対戦して確かめる手順

iOS のシミュレータと Android のエミュレータで対戦し、**両方の HP が最後まで同じになり、勝敗がそろう**ことを確かめる。
決まりは iOS のリポジトリの [battle.md](https://github.com/jphacks/os_2609-iOS/blob/feature/supabase-connection/docs/battle.md)。

## 準備

1. **同じ Supabase プロジェクトの URL と anon key** をメンバーから受け取る（service_role は使わない）。
2. **Android**：`local.properties` に書く（コミットしない）。
   ```properties
   supabase.url=https://xxxx.supabase.co
   supabase.anonKey=<anon（public）key>
   ```
   書いていないと CPU が相手のダミーになる。書いたあとは必ずビルドし直す（BuildConfig に入るため）。
3. **iOS**：`supabase/SupabaseSecrets.example.plist` をコピーして `ARApp/Resources/SupabaseSecrets.plist` を作り、同じ値を書く。
4. **データベース**：`supabase/schema.sql` が実行済みであること（Android 側からは変えない）。
5. 地点はどちらも **`entrance`（入口）**。iOS のシミュレータはダミーのビーコンで入口になり、Android は開発用に入口に固定している。

## 起動

1. Android：Android Studio で `app` をエミュレータに入れて起動する。ログは次のコマンドで見る。
   ```sh
   adb logcat -s SupabaseBattleService BattleViewModel
   ```
2. iOS：Xcode で `ARApp` をシミュレータで起動し、「バトル」タブを開く。マップに「現在地：入口」と出るまで待つ。

## 対戦（基本）

1. どちらか一方で「入口で対戦相手を探す」を押す（部屋を作って待つ側になる）。
2. **110 秒以内**に、もう一方でも「入口で対戦相手を探す」を押す。
   - 両方とも対戦画面になり、相手の名前が、相手の端末の自分のモンスター名と同じになっていること。
3. 両方の画面をタップして攻撃し合う。それぞれ 1 回以上、必殺技も使う。
4. ときどき **2〜3 秒タップを止めて**、両方の画面を見比べる。
   - Android の「自分の HP」＝ iOS の「相手の HP」
   - Android の「相手の HP」＝ iOS の「自分の HP」
   - タップを止めている間に 1 つでも食い違ったら、ルールの数値か `seq` の扱いが違う。
5. どちらかの HP が 0 になるまで続ける。
   - 片方が「勝利！」、もう片方が「敗北…」になること。
   - 負けた側の HP は、両方の画面で 0 になっていること。
   - 勝った側の HP は、負けた側の画面の方が少し低いことがある。負けた側がとどめを受け取るまでに打った攻撃は、勝った側の画面では決着後なので無視されるため（iOS 同士でも同じ。battle.md の決まりどおり）。
6. 両方で「ロビーに戻る」を押す。モンスターが選び直されること。

## データベースで確かめる

Supabase のダッシュボードの SQL Editor で実行する。

```sql
-- 最後の対戦：status が finished、winner_id が勝った側のプレイヤー ID になっている
select id, status, player1_monster, player2_monster, winner_id
from battles order by created_at desc limit 1;

-- 行動の番号に抜けがない：プレイヤーごとに count と max_seq が同じ
select player_id, count(*), max(seq) as max_seq
from battle_actions
where battle_id = (select id from battles order by created_at desc limit 1)
group by player_id;
```

## 対戦（端のケース）

| 確かめること | やり方 | こうなれば OK |
|---|---|---|
| 同時にとどめを刺す | 両方の HP を 10 以下にしてから、同時にタップする | 片方が勝利、もう片方が敗北。両方「勝利」にならない |
| 相手探しをやめる | 片方だけで相手を探し、「やめる」を押す | ロビーに戻る。`battles` の最後の行が `finished`・`winner_id` が null |
| 相手が来ない | 片方だけで相手を探し、110 秒待つ | 「対戦できませんでした」が出てロビーに戻る |
| 相手がいなくなる | 対戦中に片方のアプリを終了する | 残った側は 60 秒後に「対戦できませんでした」が出てロビーに戻る |
| 逆の順番 | 「基本」を、部屋を作る側を入れ替えてもう一度やる | 同じ結果になる |

## うまくいかないとき

- **マッチングしない**：両方の地点 ID が `entrance` か、両方とも同じ Supabase プロジェクトにつないでいるかを見る。2 分より古い部屋には入らないので、片方が待ち始めてから時間がたちすぎていないかも見る。
- **Android だけ CPU と対戦になる**：`local.properties` の値が空か、書いたあとにビルドし直していない。
- **HP が食い違う**：Android の `BattleEngine` の数値と、iOS の `BattleEngine.swift` を見比べる。ログに「Realtime に接続できませんでした」が出ていても、2 秒ごとの確認で続くので HP は食い違わない。
