# Codec Anchor

イヤホンごとに Bluetooth のコーデックと音質を決めておくと、そのイヤホンにつないだときだけ自動で適用される
Xposed モジュールです。

## できること

- イヤホンごとにコーデック、サンプルレート、ビット深度、LDAC の音質を保存できます
- 保存した設定は、そのイヤホンが接続されたときに自動で適用されます
- 項目ごとに「変更しない」を選べるので、コーデックだけ決めて残りは端末任せにもできます
- 接続のたびに端末の既定値へ戻されてしまう場合でも、反映されるまで適用をやり直します
- ほかのアプリや端末の自動設定でコーデックを変えられたときは、保存した設定へ戻します
- 使えるコーデックは端末から読み取って一覧に出すので、LHDC や MIHC のようなメーカー独自のコーデックも
  そのまま選べます

## 実行環境

- Android 12 以降
- Magisk または KernelSU で root 化された端末
- Xposed フレームワークの [Vector](https://github.com/JingMatrix/Vector)。かつての LSPosed の後継で、
  名前が Vector に変わりました。動作には [NeoZygisk](https://github.com/JingMatrix/NeoZygisk) などの
  Zygisk 環境が必要です

## 使い方

1. [Releases](https://github.com/mame1839/codec-anchor/releases) から APK を入手してインストールします
2. Vector の管理画面で Codec Anchor を有効にし、スコープに Bluetooth を追加します
3. Bluetooth を入れ直すか、端末を再起動します
4. アプリを開き、ペアリング済みのイヤホンを選んで設定します

設定は保存した時点で反映されます。接続中のイヤホンにはその場で適用されます。

## ビルド

```
./gradlew assembleDebug
```
