# Running as a service & secure remote access

**English** | [日本語](running-as-a-service.ja.md)

The dashboard only captures OTLP events while it is running — events emitted while
it is stopped are not backfilled. To get a continuous picture of your usage it
helps to keep it always-on. This guide shows how to run it as a background service
on macOS, and how to reach it from other devices over Tailscale without exposing
the unauthenticated dashboard to your whole LAN.

## Run as a background service (macOS, launchd)

Create a LaunchAgent at
`~/Library/LaunchAgents/com.example.codex-usage-dashboard.plist`. Replace
`YOUR_USERNAME` and the paths to match your install.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>com.example.codex-usage-dashboard</string>
  <key>ProgramArguments</key>
  <array>
    <!-- Prebuilt binary: -->
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

- `WorkingDirectory` is where `data/codex-usage-dashboard.sqlite` and `logs/` are
  created, so point it at a stable location.
- `RunAtLoad` starts it at login; `KeepAlive` restarts it if it exits.

If you built from source (JVM package) instead of using the prebuilt binary, swap
`ProgramArguments` for your JDK 25+ and the runnable jar:

```xml
  <key>ProgramArguments</key>
  <array>
    <string>/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home/bin/java</string>
    <string>-jar</string>
    <string>/Users/YOUR_USERNAME/codex-usage-dashboard/target/quarkus-app/quarkus-run.jar</string>
  </array>
```

Load and start it:

```sh
mkdir -p ~/codex-usage-dashboard/logs
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.example.codex-usage-dashboard.plist
launchctl kickstart -k gui/$(id -u)/com.example.codex-usage-dashboard
```

Verify and inspect:

```sh
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:4318/   # => 200
launchctl print gui/$(id -u)/com.example.codex-usage-dashboard | grep -E "state =|pid ="
```

To stop or remove it:

```sh
launchctl bootout gui/$(id -u)/com.example.codex-usage-dashboard
```

> Linux users can achieve the same with a `systemd --user` unit (`ExecStart` the
> binary or `java -jar ...`, `Restart=always`, `WantedBy=default.target`).

## Secure remote access with Tailscale Serve

The [LAN Access](../README.md#lan-access) section binds the dashboard to
`0.0.0.0`, which exposes the **unauthenticated** dashboard — including the raw-log
drill-down that can contain working directories, conversation ids, host names, and
account metadata — to every device on your network.

If you use [Tailscale](https://tailscale.com/), a safer option is to leave the app
bound to localhost (the default) and publish only the HTTP dashboard to your
tailnet with [Tailscale Serve](https://tailscale.com/kb/1242/tailscale-serve). The
gRPC receiver stays on localhost, since only the Codex/Claude Code processes on the
same machine need to reach it.

```sh
# One-time: enable Serve/HTTPS for your tailnet in the admin console if prompted.
tailscale serve --bg 4318
tailscale serve status
```

This proxies `https://<machine>.<your-tailnet>.ts.net/` to `http://127.0.0.1:4318`,
reachable only from devices on your tailnet (encrypted, not exposed on the local
LAN). The serve config persists across restarts.

Note that the direct Tailscale IP (`http://100.x.y.z:4318`) will still be refused
because the app itself listens only on localhost — use the Serve URL above.
