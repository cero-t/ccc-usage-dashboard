# アプリケーションパスと旧DBの移行

[English](application-paths.md) | **日本語**

ccc-usage-dashboard は、OS にかかわらずユーザーごとに同じホームを使います。

```text
~/.ccc-usage-dashboard/
├── config/
│   └── application.properties
├── data/
│   └── ccc-usage-dashboard.sqlite
└── logs/
    └── ccc-usage-dashboard.log
```

現在配布対象としているビルド済みバイナリは macOS Apple Silicon です。Linux でソース版または JVM 版を実行した場合も、カレントディレクトリに依存しないよう同じパスを使います。ただし、これは Linux バイナリの正式サポートを約束するものではありません。

初回起動時に親ディレクトリを作成します。通常のコンソール出力に加えて、`logs/` 以下にもアプリケーションログを書き込みます。

## アプリケーション設定

インストールしたアプリの設定には、Quarkus 標準の properties 形式を使います。既定値を変更する場合は `~/.ccc-usage-dashboard/config/application.properties` を作成し、必要なプロパティだけを追加してください。このファイルは Quarkus が解析し、ccc-usage-dashboard は独自の設定ファイルパーサーを実装しません。

アプリケーションホーム全体は、プロセス環境変数で変更できます。

```sh
CCC_USAGE_DASHBOARD_HOME=/absolute/path/to/ccc-home ./ccc-usage-dashboard
```

起動前に必要なパスも、プロセス環境変数で個別に変更できます。

| 変数 | 用途 |
| --- | --- |
| `CCC_USAGE_DASHBOARD_CONFIG_FILE` | 読み込む外部 `application.properties` ファイル |
| `CCC_USAGE_DASHBOARD_DATA_DIR` | データディレクトリ |
| `CCC_USAGE_DASHBOARD_DATABASE_PATH` | SQLite ファイル |
| `CCC_USAGE_DASHBOARD_LOG_DIR` | ログディレクトリ |
| `QUARKUS_DATASOURCE_JDBC_URL` | JDBC URL 全体。指定時は旧DBの自動移行を無効化 |

ccc-usage-dashboard のパス上書き設定では `~`、`$HOME`、`${HOME}` を展開します。`QUARKUS_DATASOURCE_JDBC_URL` には絶対パスを指定してください。

設定は次の順で解決します。

1. JVM システムプロパティ（`-D...`）
2. プロセスの環境変数
3. カレントディレクトリの `.env`（ローカル開発専用）
4. `~/.ccc-usage-dashboard/config/application.properties`（ordinal 290）
5. `$PWD/config/application.properties`
6. アプリケーションに組み込まれた既定値

このため、手動起動とバックグラウンド起動で同じ外部設定ファイルが有効になります。`CCC_USAGE_DASHBOARD_HOME` と `CCC_USAGE_DASHBOARD_CONFIG_FILE` は設定ファイルを探す前に必要なので、プロセス環境変数または JVM プロパティで指定します。

既存の `CODEX_USAGE_DASHBOARD_*` 環境変数は v0.3 の移行期間中も利用でき、新旧両方を指定した場合は `CCC_USAGE_DASHBOARD_*` を優先します。`quarkus.datasource.jdbc.url` プロパティまたは `QUARKUS_DATASOURCE_JDBC_URL` 環境変数を明示した場合は、常にアプリの既定DBパスより優先されます。

## 旧データベースの移行

v0.3.0 より前の既定DBは次の場所でした。

```text
<カレントディレクトリ>/data/codex-usage-dashboard.sqlite
```

JDBC URL を明示していない場合、起動時に次の規則を適用します。

1. 新しい固定パスにDBがあれば、それを使い、上書きしません。
2. 新DBがなく、起動時のカレントディレクトリに旧DBがあれば、SQLite の整合したバックアップを新パスに作成し、`PRAGMA quick_check` で検証してから選択します。
3. どちらもなければ、新しい固定パスに空のDBを作成します。

旧DBから作成したコピーは、検証が完了してから新しいDBとして配置します。旧ファイルは削除しないため、そのままロールバック用コピーになります。2回目以降の起動では移行先を旧DBで置き換えないので、移行は冪等です。判断結果とパスはログに記録しますが、JDBC URL や設定値は出力しません。

旧DBが別の作業ディレクトリにある場合は、そのディレクトリから v0.3.0 を一度起動するか、`QUARKUS_DATASOURCE_JDBC_URL` で旧DBを明示してください。JDBC URL を明示した場合、自動移行は意図的に実行しません。

## バックアップ、ロールバック、アンインストール

データベースを手動でバックアップする場合は、作業を始める前に ccc-usage-dashboard を停止してください。SQLite の `.backup` コマンドを使うか、DB本体と `-wal`、`-shm` のサイドカーファイルを一緒にコピーする方法が安全です。

自動移行後に旧DBへ戻す場合は、ccc-usage-dashboard を停止し、残してある旧ファイルを `QUARKUS_DATASOURCE_JDBC_URL` で指定します。使用するDBを確認するまでは、どちらのコピーも削除しないでください。

実行ファイルや LaunchAgent を削除してもユーザーデータは消えません。ccc-usage-dashboard を完全に削除する場合は、アプリを停止して必要なバックアップを取り、`~/.ccc-usage-dashboard` を削除します。旧作業ディレクトリのDBはこの外にあるため、不要なら別途削除します。
