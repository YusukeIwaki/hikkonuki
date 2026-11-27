# Hikkonuki

[English](README.md) | 日本語

Osmo Pocket 4P / Insta360 Ace Pro 2 を **USB** でAndroidにつなぎ、写真と動画をスマートフォンへ取り込むアプリです。
カメラをつないで、取り込みたいものを選んでエクスポートするだけです。動画は2KのHEVCに変換し、写真はそのままコピーして、`DCIM/Hikkonuki` に保存します。Googleフォトが見える場所なので、アップロードはGoogleフォトに任せます。

- パッケージ: `app.hikkonuki.android`
- 対応言語: 英語（既定）、日本語、中国語（簡体字・繁体字）、韓国語。端末の言語、またはシステム設定の「アプリの言語」（Android 13以降）に従います。

## 動画の変換

もともとMac上の ffmpeg でカメラごとに調整していた変換の方針を、端末向けに移植しています。

| 項目 | 内容 |
| --- | --- |
| 解像度 | 長辺2560まで。縦横比を保ち、拡大しない（Insta360の1080pは1080pのまま） |
| コーデック | HEVC。10bit素材はMain10のまま（端末のエンコーダが対応している場合） |
| 縮小 | GPU上のLanczos-3（2パス） |
| 色 | `GL_EXT_YUV_target` 対応端末では、Y'CbCrからRGBへの変換をシェーダーで明示的に行う。Insta360のフルレンジ素材をリミテッドレンジと取り違えないため |
| ビットレート | 1080p30で8Mbps、1440p30で12Mbps。Osmo Pocketは元動画のビットレート（＝絵の細かさ）に応じて最大4倍まで上げる |
| 音声 | 再エンコードせずコピー |
| 撮影日時 | コンテナの日時とファイル名の時刻から決定し、MP4（`mvhd` / `tkhd` / `mdhd`）に書き込む。メーカー・機種名も記録 |
| 変換しない場合 | HDR/Log素材、圧縮しても20%以上減らない素材、変換に失敗した素材は元動画をそのまま取り込む |

変換は `direct-10bit` → `direct-8bit` → Media3 Transformer の順に試し、どれも失敗したら元動画を取り込みます。

### 撮影日時の決め方

コンテナの日時（UTC）と、ファイル名の時刻（カメラの現地時刻）を比べます。

- 差がタイムゾーンとしてありえる値（15分単位）: コンテナの日時を採用し、差をカメラのタイムゾーンとして記録
- 同じ数字: DJIはコンテナの日時、それ以外はファイル名を端末のタイムゾーンで解釈
- それ以外（未設定の1904年/1970年など）: ファイル名を採用

Androidのメディアスキャナ（＝Googleフォトの並び順）は動画ファイル内の日時を読むため、公開前にファイルへ書き込んでいます。

## 構成

画面とエクスポート処理は互いを知りません。画面は `ExportController` の一覧（`jobs` / `batches`）を表示して、コマンドを送るだけです。

```
core/      Androidに依存しない純Kotlin。JVMの単体テストで網羅
  ExportQueue    エクスポートの一覧とルール（バッチ、重複排除）
  ExportEngine   1件ずつ取り込む。中断・USB抜け・キャンセル・フォールバック
  SlimPlanner    解像度とビットレートの決定
  CaptureDate    撮影日時の決定
  Mp4            MP4メタデータの読み書き
platform/  Android実装（USBフォルダ、MediaStore、SQLite、MediaCodec+OpenGL、サービス）
ui/        Compose画面とViewModel（MediaLibrary / ExportController のみに依存）
```

### エクスポートの状態

- 実行中のバッチ（ACTIVE）は1件ずつの行を持ち、エクスポート状況に一覧表示されます。
- すべて終わるとバッチはDONEになり、行は削除されて結果（N件中M件成功）だけが残ります。保持するバッチは最大2つです。
- キャンセルした行は削除されます。「履歴を消す」ですべて消えます。

中断への強さ:

- 状態はその都度SQLiteに保存します。カメラがつながったままプロセスが落ちた場合は、次回起動時に処理中だったファイルを待機に戻して続きから処理します。その時点でカメラが切れていれば、残りを失敗にして起動時にバッチを閉じます。
- 出力はMediaStoreの保留（pending）ファイルに直接書くので、書きかけのファイルは他のアプリから見えません。残骸は次回起動時に削除します。
- USBの切断は、システムのブロードキャスト（`USB_DEVICE_DETACHED`、`MEDIA_EJECT` / `UNMOUNTED` / `REMOVED` / `BAD_REMOVAL`）と、元ファイルを1秒ごとに確認する監視（2回連続で開けなければ切断）の両方で検知します。
- 転送中にUSBが抜けると、処理中のファイルと待機中のファイルをまとめて失敗にしてバッチを終わらせます（4件中3件目で切れたら「4件中 2件成功、2件失敗」）。つなぎ直して同じものをエクスポートし直すと、取り込み済みのファイルは飛ばします。

## カメラの読み取り

システムのフォルダ選択で一度だけカメラの `DCIM` を許可します。許可はSDカードごとに記憶するので、2台目のカメラも最初の1回だけです。ストレージ権限は使いません。

## 言語

文言はすべて文字列リソースにあります: `values/`（英語）、`values-ja/`、`values-zh/`（簡体字）、`values-b+zh+Hant/`（繁体字）、`values-ko/`。`xml/locales_config.xml` が、システムの「アプリの言語」設定に出す言語の一覧です。製品名「Hikkonuki」は翻訳しません。

エクスポートの失敗理由は文章ではなく理由コードで保存するので、ある言語で記録された行も、言語を切り替えたあと正しく表示されます。`LocalizationTest` で、すべての文字列が全言語に翻訳されていること、書式の埋め込み記号が一致していることを確認しています。

## ビルドとテスト

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug

# 単体テスト（JVM / Robolectric）: エクスポートのロジック、日時、MP4、ViewModel、画面、翻訳
./gradlew :app:testDebugUnitTest

# 端末テスト: 実コーデックでの変換、MediaStore保存、日時、色、アプリ全体の流れ
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

端末テストはカメラなしで動きます。エミュレータではHEVCエンコーダが512×512まで・10bitデコード不可のため、次の3件はスキップされ、実機でだけ走ります。

- `directPipelineTenBitTwoK`（4K 10bit → 2K HEVC Main10）
- `tenBitColorsSurvive`
- `fullRangeColorsSurvive`（Insta360のフルレンジ。エミュレータはMedia3でも同じくレンジを取り違える）

実機で流す場合は、スマートフォンだけをつないで `ANDROID_SERIAL=<シリアル> ./gradlew :app:connectedDebugAndroidTest` を実行します。

### カメラなしで画面を触る

デバッグビルドは、アプリの `usb_inbox` フォルダに置いたファイルを接続中のカメラとして扱います。

```bash
adb push clip.mp4 /sdcard/Android/data/app.hikkonuki.android/files/usb_inbox/
```

### テスト用ファイルの作り直し

```bash
# カメラの実ファイルからヘッダだけ抜いたMP4（単体テスト用）。元動画はリポジトリに含めていません
python3 tools/make_fixtures.py --insta360 VID_xxx.mp4 --dji DJI_xxx.MP4
tools/make_test_videos.sh        # カメラの形式に似せた合成動画（端末テスト用）
python3 design/generate_icon.py  # アイコン
```
