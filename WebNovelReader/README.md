# 小説リーダー（カクヨム・なろう オフライン保存アプリ）

共有メニューから作品URLを渡すと、最初の話から「次のエピソード」リンクを自動で辿って
全話の本文をオフライン保存するAndroidアプリ。

## できること
- カクヨム / なろう(syosetu.com) の作品URLを共有 → 自動で全話ダウンロード
- アプリ内で保存済み作品の一覧・話一覧・本文閲覧
- 再度同じ作品を追加すると、保存済みの続きからダウンロード再開
- **任意のURLをフルページ（縦長）スクショしてPNG保存**（小説データとは別フォルダ）
  - Node.js/Puppeteerではなく、Android標準のWebViewでページ全体を描画してBitmap化する方式
  - 遅延読み込み対策として一度ページ最下部までスクロールしてから撮影
  - 保存先は `Android/data/app.novelreader/files/screenshots/`（アプリ専用領域）
  - 「共有／保存」ボタンでギャラリー等の好きな場所に書き出せる
- **年齢確認（アダルト作品等のゲート）があるサイトへの対応**
  - メイン画面の「年齢確認が必要なサイトを開く」から、実際に操作できるWebViewでサイトを開く
  - ユーザーが手動で「はい」等をタップして通過 →「確認完了・保存」を押すとそのドメインのCookieを保存
  - 以降はダウンロード時にそのCookieを自動で使うので、毎回ブラウザを開き直す必要はない
  - 年齢確認ページが出たままダウンロードしようとした場合は、その旨のエラーメッセージが出るようにしてある
- **ダウンロードは通知バーに進捗表示されるフォアグラウンドサービスで実行**
  - 「12/600話」のように現在数/総数が通知に出る（プログレスバー付き）
  - アプリを閉じたり画面を消してもサービスが継続しやすくなり、以前より安定してダウンロードできる
  - なろうは作品ページの「全◯◯エピソード」表記から総話数を取得し、1話目から最終話まで
    番号を直接指定して巡回する（404の判定に頼らないので「今どこまで進んでいるか」が正確に分かる）
  - カクヨム、または総話数が読み取れない場合は、従来通り「次のエピソード」リンクを辿る方式にフォールバックする
- **サイトごとのアクセス間隔調整**：カクヨム・なろうそれぞれに適した間隔でアクセスするレートリミッターを搭載
  （一律の待機時間より、サイト別に最適化した方がアクセス制限に引っかかりにくい）
- **通知チャンネルを目的別に分離**：「進捗」「結果」「新着エピソード」の3チャンネル。OS標準の通知設定から
  個別にミュート等の制御が可能
- **URLの正規化**：モバイル版ドメインの統一、トラッキングパラメータの除去、末尾スラッシュ補完などを
  ダウンロード開始前に自動で行う
- **バックグラウンド自動更新**：WorkManagerで6時間おきに登録済み作品の新着をチェックし、
  見つかれば自動でダウンロードして通知（アプリを開いていなくても動作）
- **複数URLの一括追加**：メイン画面の「複数URLをまとめて追加」から、1行1URLで貼り付けると
  キューに積んで順番にダウンロード（同時並行ではなく直列処理でサイト負荷を抑える）
- **読み上げ（TTS）**：話を開いた画面の「読み上げ開始」ボタンで本文をAndroid標準の音声合成で読み上げ
- **続きから読む（しおり）**：話一覧画面に、最後に開いた話とスクロール位置を記録した
  「続きから読む」ボタンが自動で表示される

## 制約・注意点（正直に）
- 本文抽出は「同じ場所に<p>タグが一番密集している場所を本文とみなす」汎用ロジックです。
  実際のカクヨム作品ページ・話ページの構造をこちらで確認した上で書いていますが、
  サイトのデザインが変わると抽出がずれる可能性があります。その場合は
  `NovelScraper.kt` の `extractEpisodeBody` を調整してください。
- なろうは URL が `ncode.syosetu.com/{ncode}/{話数}/` という連番構成である前提で、
  1話ずつ番号を+1しながら取得しています（未実在ページに当たったら終了とみなします）。
- サーバー負荷軽減のため1話ごとに0.35秒待機します。数百〜数千話ある作品は
  ダウンロードに数分〜数十分かかります。
- 利用規約でスクレイピングが禁止されていないか、各サイトの規約は各自ご確認ください。
  個人の私的利用の範囲で使うことを想定しています。

## Termuxでのビルド手順

Termux単体でAndroid SDK一式を入れてAPKを作る手順です。**初回は数GBのダウンロードが
発生し、スマホのスペックによってはビルドに時間がかかります。** 途中でエラーが出た場合は
エラーメッセージをそのまま貼ってもらえれば一緒に直せます。

```bash
# 1. パッケージ更新＆必要なツールのインストール
pkg update -y && pkg upgrade -y
pkg install -y openjdk-17 gradle git wget unzip

# 2. Android SDK コマンドラインツールを取得
mkdir -p ~/android-sdk/cmdline-tools
cd ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip
mv cmdline-tools latest

# 3. 環境変数を設定（.bashrc に追記して毎回読み込まれるようにする）
echo 'export ANDROID_HOME=$HOME/android-sdk' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools' >> ~/.bashrc
source ~/.bashrc

# 4. 必要なSDKコンポーネントを取得（ライセンス同意が必要）
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# 5. プロジェクトフォルダに移動してビルド
cd /path/to/WebNovelReader   # このフォルダをTermuxからアクセスできる場所に置く
gradle assembleDebug

# 6. 完成したAPKの場所
# app/build/outputs/apk/debug/app-debug.apk
```

ビルドが終わったら `app-debug.apk` をタップしてインストールしてください
（提供元不明のアプリのインストールを許可する必要があります）。

### プロジェクトフォルダをTermuxから触れる場所に置く方法
Termuxはデフォルトでは端末の共有ストレージにアクセスできません。まず

```bash
termux-setup-storage
```

を実行して権限を許可し、ダウンロードフォルダに展開したこのzipを
`~/storage/downloads/WebNovelReader` のようなパスから
`cp -r` でTermuxのホーム以下にコピーしてから `gradle assembleDebug` を実行してください。

### よくあるエラー
- `SDK location not found`: `local.properties` に `sdk.dir=/data/data/com.termux/files/home/android-sdk`
  を書いた `local.properties` ファイルをプロジェクト直下に作ると解決することがあります。
- メモリ不足で落ちる: `gradle.properties` の `org.gradle.jvmargs` の数値を下げてみてください
  （例: `-Xmx1024m`）。
- ビルドが重すぎて現実的でない場合: PCでAndroid Studioを使ってこのフォルダを開く方が
  圧倒的に速くて安定します。
