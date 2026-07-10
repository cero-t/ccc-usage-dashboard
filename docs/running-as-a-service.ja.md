# サービスとしての実行と安全なリモートアクセス

[English](running-as-a-service.md) | **日本語**

このダッシュボードがOTLPイベントを記録できるのは、プロセスが起動している間だけです。停止中に送信されたイベントは後から取り込まれません。利用状況を継続的に記録するには、ダッシュボードを常時起動しておく必要があります。このガイドでは、macOSでバックグラウンドサービスとして実行する方法と、認証のないダッシュボードをLAN全体へ公開せずにTailscale経由で他の端末から利用する方法を説明します。

## バックグラウンドサービスとして実行する（macOS、launchd）

`~/Library/LaunchAgents/com.example.codex-usage-dashboard.plist` にLaunchAgentを作成します。`YOUR_USERNAME`と各パスは、実際のインストール先に合わせて置き換えてください。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>com.example.codex-usage-dashboard</string>
  <key>ProgramArguments</key>
  <array>
    <!-- 配布済みバイナリ: -->
    <string>/Users/YOUR_USERNAME/codex-usage-dashboard/codex-usage-dashboard</string>
  </array>
  <key>WorkingDirectory</key>
  <string>/Users/YOUR_USERNAME/codex-usage-dashboard</string>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>ProcessType</key>
  <string>Background</string>
  <key>StandardOutPath</key>
  <string>/Users/YOUR_USERNAME/codex-usage-dashboard/logs/dashboard.out.log</string>
  <key>StandardErrorPath</key>
  <string>/Users/YOUR_USERNAME/codex-usage-dashboard/logs/dashboard.err.log</string>
</dict>
</plist>
```

- `WorkingDirectory`を基準として`data/codex-usage-dashboard.sqlite`と`logs/`が作成されるため、安定した場所を指定してください。
- `RunAtLoad`によりログイン時に起動し、`KeepAlive`によりプロセス終了時に再起動します。

配布済みバイナリではなく、ソースからJVMパッケージをビルドした場合は、`ProgramArguments`をJDK 25以降と実行可能JARのパスに置き換えます。

```xml
  <key>ProgramArguments</key>
  <array>
    <string>/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home/bin/java</string>
    <string>-jar</string>
    <string>/Users/YOUR_USERNAME/codex-usage-dashboard/target/quarkus-app/quarkus-run.jar</string>
  </array>
```

LaunchAgentを読み込み、起動します。

```sh
mkdir -p ~/codex-usage-dashboard/logs
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.example.codex-usage-dashboard.plist
launchctl kickstart -k gui/$(id -u)/com.example.codex-usage-dashboard
```

稼働状態を確認します。

```sh
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:4318/   # => 200
launchctl print gui/$(id -u)/com.example.codex-usage-dashboard | grep -E "state =|pid ="
```

停止または登録解除する場合は、次のコマンドを実行します。

```sh
launchctl bootout gui/$(id -u)/com.example.codex-usage-dashboard
```

> Linuxでは、`systemd --user`ユニットでも同様に常駐化できます。`ExecStart`にバイナリまたは`java -jar ...`を指定し、`Restart=always`と`WantedBy=default.target`を設定します。

## Tailscale Serveによる安全なリモートアクセス

[LANからのアクセス](../README.ja.md#lan-からのアクセス)で説明している`0.0.0.0`へのバインドは、**認証のない**ダッシュボードをLAN上のすべての端末へ公開します。生ログのドリルダウンには、作業ディレクトリ、会話ID、ホスト名、アカウントメタデータなどが含まれる可能性があります。

[Tailscale](https://tailscale.com/)を利用している場合は、アプリを既定どおりlocalhostで待ち受けたまま、HTTPダッシュボードだけを[Tailscale Serve](https://tailscale.com/kb/1242/tailscale-serve)でtailnetへ公開する方が安全です。gRPCレシーバーは同じマシン上のCodex／Claude Codeプロセスだけが利用するため、localhostのままにします。

```sh
# 初回のみ、必要に応じて管理画面でtailnetのServe／HTTPSを有効化します。
tailscale serve --bg 4318
tailscale serve status
```

これにより、`https://<machine>.<your-tailnet>.ts.net/`から`http://127.0.0.1:4318`へプロキシされます。アクセスできるのはtailnet内の端末だけで、通信は暗号化され、ローカルLANには公開されません。Serveの設定は再起動後も維持されます。

アプリ自体はlocalhostだけで待ち受けるため、TailscaleのIPアドレスへ直接アクセスする`http://100.x.y.z:4318`は接続を拒否されます。上記のServe URLを利用してください。
