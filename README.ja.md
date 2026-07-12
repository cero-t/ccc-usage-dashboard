# ccc-usage-dashboard

[English](README.md) | **日本語**

**CCC (Codex and Claude Code) Usage Dashboard** は、Codex と Claude Code の利用状況、コスト、利用枠、利用主体(trigger)をローカルで確認できる、単一バイナリのダッシュボードです。

ダッシュボード上では Codex / Claude Code を別タブで表示し、利用履歴、トークン数、USD コスト推定、利用主体の内訳を確認できます。Codex の USD コストは 1000 credits = $40 として概算し、Claude Code の USD コストは Claude Code テレメトリと Claude API の料金表から計算します。

## ダッシュボード例

以下は 12時間 / 5分刻みで表示したスクリーンショットです。

### Codex

![12時間 / 5分刻みの Codex ダッシュボードのチャート](docs/assets/codex-dashboard-12h-5m.jpg)

### Claude Code

![12時間 / 5分刻みの Claude Code ダッシュボードのチャート](docs/assets/claude-dashboard-12h-5m.jpg)

## 必要なもの

- Codex タブ: Codex の OTLP エクスポート設定([Codex の OTLP 設定](#codex-の-otlp-設定))
- Codex 利用率(%)ゲージ: Codex CLI がインストール済み、サインイン済み
- Claude Code タブ: Claude Code のテレメトリ設定([Claude Code の OTLP 設定](#claude-code-の-otlp-設定))

どちらか一方のツールだけでも動かせます。Codex を使わないマシンでは `CCC_USAGE_DASHBOARD_CODEX_ENABLED=false`、Claude Code を使わないマシンでは `CCC_USAGE_DASHBOARD_CLAUDE_ENABLED=false` を設定してください。

## クイックスタート

ビルド済みバイナリは **macOS 専用**(Apple Silicon)です。[Releases](../../releases) ページからダウンロードして実行します:

```sh
unzip ccc-usage-dashboard-macos-arm64.zip
chmod +x ccc-usage-dashboard
./ccc-usage-dashboard
```

`v0.2.0`以前のリリースバイナリは署名・notarize していません。macOS で "Apple could not verify" と表示されて起動できない場合、ダウンロードしたファイルを信頼できるときだけ quarantine 属性を外して再実行してください:

```sh
xattr -d com.apple.quarantine ccc-usage-dashboard
./ccc-usage-dashboard
```

`v0.3.0`以降のリリースは署名・notarize済みのため、この手順は不要です。

別のプラットフォームを使う場合や、自分でビルドしたい場合は、ソースからビルドしてください — [`dev_docs/development.md`](dev_docs/development.md) を参照。

ブラウザで開く:

```text
http://127.0.0.1:4318/
```

画面上部のタブで Codex / Claude Code を切り替えます。期間は相対期間、現在の Codex 5h 利用枠、任意の from/to 範囲から選べます。

多くのチャートと利用状況テーブルは、パネルごとに **Cost / Tokens** を切り替えられます。ある内訳は USD コストで見つつ、別の内訳はトークン数のまま確認できます。

既定では localhost のみで待ち受けます:

- OTLP gRPC: `127.0.0.1:4317`
- HTTP ダッシュボード / OTLP/HTTP protobuf: `127.0.0.1:4318`

ローカルデータベースは次の場所に作成されます:

```text
~/.ccc-usage-dashboard/data/ccc-usage-dashboard.sqlite
```

設定ファイルは `~/.ccc-usage-dashboard/config/application.properties`、ログは `~/.ccc-usage-dashboard/logs/` に置きます。v0.3 の初回起動時、新DBがまだなければ既存の `./data/codex-usage-dashboard.sqlite` を安全にコピーします。優先順位、上書き設定、バックアップ、ロールバックの詳細は[アプリケーションパスと旧DBの移行](docs/application-paths.ja.md)を参照してください。

## Codex の OTLP 設定

基本構成では、Codex の OTLP ログ出力をこのダッシュボードの gRPC レシーバーへ直接送ります。`~/.codex/config.toml` を編集します:

```toml
[otel]
exporter = { otlp-grpc = { endpoint = "http://127.0.0.1:4317" } }
```

設定変更後は Codex を再起動してください。新しい Codex のアクティビティが、1分ほどでダッシュボードに反映され始めます。

新しい OTLP イベントを記録するには、Codex や Claude Code を使っている間このダッシュボードのプロセスを起動しておく必要があります。停止中に送信されたイベントは後から取り込まれません。自動的にバックグラウンドで常時実行する方法は、[サービスとしての実行](docs/running-as-a-service.ja.md)を参照してください。

## Claude Code の OTLP 設定

Claude Code は、同じローカル gRPC レシーバーへ OTLP log/events を送信します。トークン数とコストのチャートは Claude Code テレメトリから作成します。

継続的に使う場合は、`~/.claude/settings.json` を編集し、既存の `env` オブジェクトに次の設定を追加します:

```json
{
  "env": {
    "CLAUDE_CODE_ENABLE_TELEMETRY": "1",
    "OTEL_LOGS_EXPORTER": "otlp",
    "OTEL_EXPORTER_OTLP_PROTOCOL": "grpc",
    "OTEL_EXPORTER_OTLP_ENDPOINT": "http://127.0.0.1:4317"
  }
}
```

設定変更後は Claude Code を再起動するか、新しいセッションを開始してください。

一時的に試すだけなら、`claude` を起動する前に同じ値をシェルで設定します:

```sh
export CLAUDE_CODE_ENABLE_TELEMETRY=1
export OTEL_LOGS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_PROTOCOL=grpc
export OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4317
claude
```

Claude Code のコストは、テレメトリに含まれるトークン数から計算します。Claude Code 側が `cost_usd` も送っている場合、その値は比較用に別途保持します。

Claude Code 側の詳細なテレメトリ設定は [Claude Code Monitoring docs](https://code.claude.com/docs/en/monitoring-usage) を参照してください。

## すでに他のオブザバビリティ基盤へ OTLP を送っている場合

このダッシュボードは標準の OTLP ポート(gRPC `:4317`、HTTP `:4318`)で待ち受けるため、Codex と Claude Code から直接ここへ送れます。すでに [OpenTelemetry Collector](https://opentelemetry.io/docs/collector/) を同じポートで動かしている場合は、各ツールは Collector に向けたままにして、そこからこのダッシュボードへコピーを分岐(fan-out)させ、このダッシュボードを標準ポートからずらしてください。

固定の上書き設定は `~/.ccc-usage-dashboard/config/application.properties` に置きます:

```properties
# ~/.ccc-usage-dashboard/config/application.properties
quarkus.grpc.server.port=14317
quarkus.http.port=14318
```

そのうえで、Collector から OTLP/HTTP protobuf を `http://127.0.0.1:14318/v1/logs`(または gRPC を `:14317`)へ送信させます。

## LAN からのアクセス

このダッシュボードは既定でローカル専用です。**認証はありません**。また生ログのドリルダウン(`/api/events/{id}/raw`)は、作業ディレクトリ・会話ID・ホスト名・アカウントメタデータなどを含みうる OTLP レコードをそのまま返します。LAN に公開すると、**ネットワーク上のあらゆる端末が認証なしでそれらのデータを読める**状態になります — 信頼できるネットワークでのみ行ってください。

意図的に LAN へ公開する場合:

```sh
QUARKUS_HTTP_HOST=0.0.0.0 \
QUARKUS_GRPC_SERVER_HOST=0.0.0.0 \
./ccc-usage-dashboard
```

他の端末から `http://<machine-ip>:4318/` を開きます。

認証のないダッシュボードをLAN全体へ公開せずにリモートアクセスする場合は、localhostでの待受を維持したまま[Tailscale Serveを利用する方法](docs/running-as-a-service.ja.md)を参照してください。

## 設定

インストールしたアプリの設定ファイルは `~/.ccc-usage-dashboard/config/application.properties` です。既定値を変更する場合にこのファイルを作成してください。優先順位と移行規則の詳細は[アプリケーションパスと旧DBの移行](docs/application-paths.ja.md)を参照してください。よく使うプロパティ:

```properties
# ツールごとの取り込みフラグ
ccc-usage-dashboard.codex.enabled=true
ccc-usage-dashboard.claude.enabled=true

# ポート / バインドアドレス
quarkus.http.host=127.0.0.1
quarkus.http.port=4318
quarkus.grpc.server.host=127.0.0.1
quarkus.grpc.server.port=4317

# 任意のデータベース
quarkus.datasource.jdbc.url=jdbc:sqlite:/absolute/path/custom.sqlite?journal_mode=WAL&busy_timeout=10000

# ローカルテレメトリの保持期間
ccc-usage-dashboard.retention.every=1h
ccc-usage-dashboard.retention.otel-log-records=14d
ccc-usage-dashboard.retention.annotated-events=365d
ccc-usage-dashboard.retention.usage-samples=365d

# Codex のローカルデータディレクトリ
codex.db.dir=/Users/you/.codex
codex.bin=codex

# ノイズの多い OTLP log レコードの受信時 drop filter
ccc-usage-dashboard.ingest.drop-event-kinds=^response\\..+\\.delta$

# 詳細チューニング
ccc-usage-dashboard.annotate.every=60s
ccc-usage-dashboard.annotate.batch-size=500
ccc-usage-dashboard.usage.every=60s
```

各プロパティは、Quarkus／MicroProfile の規則に対応する環境変数でも上書きできます。既存の `CODEX_USAGE_DASHBOARD_*` 名は v0.3 の移行期間中も利用でき、新旧両方を指定した場合は `CCC_USAGE_DASHBOARD_*` を優先します。

Codex がないマシンでは `CCC_USAGE_DASHBOARD_CODEX_ENABLED=false` にすると、Codex の OTLP ログを無視し、Codex 利用枠の定期取得も止めます。そのため `codex` コマンドの起動も試みません。Claude Code の OTLP ログを無視したい場合は `CCC_USAGE_DASHBOARD_CLAUDE_ENABLED=false` を設定します。無効化したツールはダッシュボードの切り替えにも表示しません。

保持期間は、ローカルに保存するデータの種類ごとに独立して適用されます。

- 生の OTLP log レコード: Codex と Claude Code から届いた元の OTLP log payload です。生ログのドリルダウンに使い、通常は最も大きくなりやすいデータです。設定した保持期間は削除対象にする目安であり、それより古いレコードが必ず即時に消えるとは限りません。
- ダッシュボード履歴: チャートや一覧に使う、トークン数・コスト・trigger・エラーのデータです。
- Codex 利用枠サンプル: Codex 利用枠の割合を定期取得したスナップショットです。

各保持期間は `0` または `disabled` にすると無期限保持になります。古い生の OTLP log レコードを削除しても、すでに作成済みのチャート履歴は残ります。ただし、それらの古いイベントの生ログドリルダウンは表示できなくなります。SQLite は削除済み領域を再利用しますが、ファイルサイズがすぐ縮むとは限りません。ディスク上のファイルを縮めたい場合は手動で `VACUUM` を実行してください。

既定では、`event.kind` が `^response[.].+[.]delta$` に一致する高頻度の Codex streaming delta レコードは受信時に破棄します。これらの行はトークン利用量を持たず、completed request レコードより大幅に多くなることがあります。受信した OTLP log レコードをすべて保存したい場合は `ccc-usage-dashboard.ingest.drop-event-kinds` を空にしてください。

## OTLP サポート

対応:

- `:4317` での OTLP/gRPC
- `:4318/v1/logs` での OTLP/HTTP protobuf
- gzip 圧縮された OTLP/HTTP protobuf ボディ
- トークン数と `cost_usd` を含む Claude Code OTLP log `api_request` レコード

ダッシュボードのチャートは OTLP logs をもとに作ります。metrics や traces は受信できても、ダッシュボードのチャートには表示しません。

非対応:

- OTLP/HTTP JSON(`Content-Type: application/json`)

OTLP/HTTP JSON のリクエストは `415 Unsupported Media Type` を返します。これは、設定を誤ったエクスポーターが「成功したように見えて実際にはデータを黙って捨てている」状態を防ぐためです。OTLP/HTTP エクスポーターは JSON ではなく protobuf(`encoding: proto`)で送るようにしてください。

## 開発ドキュメント

開発者向けのメモは [`dev_docs/`](dev_docs/) にあります。まずは次から:

- [`dev_docs/architecture.md`](dev_docs/architecture.md)
- [`dev_docs/development.md`](dev_docs/development.md)
- [`dev_docs/token-accounting.md`](dev_docs/token-accounting.md)

## ライセンス

[Apache License, Version 2.0](LICENSE) の下で提供しています。同梱する第三者コンポーネント(Apache ECharts)の帰属表示は [`NOTICE`](NOTICE) を参照してください。
