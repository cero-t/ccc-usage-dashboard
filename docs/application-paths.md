# Application paths and legacy migration

**English** | [日本語](application-paths.ja.md)

ccc-usage-dashboard uses one per-user application home on every operating
system:

```text
~/.ccc-usage-dashboard/
├── config/
│   └── application.properties
├── data/
│   └── ccc-usage-dashboard.sqlite
└── logs/
    └── ccc-usage-dashboard.log
```

The current prebuilt release target is macOS Apple Silicon. Source and JVM runs
on Linux use the same paths so that runtime behavior does not depend on the
current working directory; this path compatibility is not a promise of a
supported Linux binary.

The application creates the parent directories on first launch. It continues to
write normal console output and also writes its application log under `logs/`.

## Application configuration

Installed application settings use Quarkus's standard properties format. Create
`~/.ccc-usage-dashboard/config/application.properties` when you need to override
a default, and add only the properties you need. Quarkus parses this file;
ccc-usage-dashboard does not implement a separate configuration-file parser.

The complete application home can be moved with:

```sh
CCC_USAGE_DASHBOARD_HOME=/absolute/path/to/ccc-home ./ccc-usage-dashboard
```

Bootstrap path overrides are also available as process environment variables:

| Variable | Purpose |
| --- | --- |
| `CCC_USAGE_DASHBOARD_CONFIG_FILE` | External `application.properties` file to load |
| `CCC_USAGE_DASHBOARD_DATA_DIR` | Data directory |
| `CCC_USAGE_DASHBOARD_DATABASE_PATH` | SQLite file path |
| `CCC_USAGE_DASHBOARD_LOG_DIR` | Log directory |
| `QUARKUS_DATASOURCE_JDBC_URL` | Complete JDBC URL; disables automatic legacy migration |

`~`, `$HOME`, and `${HOME}` are expanded in the ccc-usage-dashboard path overrides. Use an
absolute path in `QUARKUS_DATASOURCE_JDBC_URL`.

Configuration sources are resolved in this order:

1. JVM system properties (`-D...`)
2. process environment variables
3. a `.env` in the current working directory (local development only)
4. `~/.ccc-usage-dashboard/config/application.properties` (ordinal 290)
5. `$PWD/config/application.properties`
6. bundled application defaults

The external application file therefore has the same effect for manual and
background execution. `CCC_USAGE_DASHBOARD_HOME` and
`CCC_USAGE_DASHBOARD_CONFIG_FILE` must be supplied as a process environment
variable or JVM property because they are needed before the file can be located.

All existing `CODEX_USAGE_DASHBOARD_*` environment variables remain supported
during the v0.3 transition. The equivalent `CCC_USAGE_DASHBOARD_*` name is
preferred when both names are present. An explicit
`quarkus.datasource.jdbc.url` property or `QUARKUS_DATASOURCE_JDBC_URL`
environment variable always wins over the application's default database path.

## Legacy database migration

Before v0.3.0, the default database was:

```text
<working-directory>/data/codex-usage-dashboard.sqlite
```

At startup, ccc-usage-dashboard applies these rules when no explicit JDBC URL is configured:

1. If the stable destination database exists, use it and do not overwrite it.
2. Otherwise, if the legacy database exists under the startup working
   directory, create a consistent SQLite backup at the stable destination,
   verify it with `PRAGMA quick_check`, and select it.
3. Otherwise, create a fresh database at the stable destination.

The copy is published at the destination only after verification. The legacy
file is never deleted, so it remains an immediate rollback copy. Repeated starts
are idempotent: once the destination exists it is never replaced by the legacy
database. Decisions and paths are logged, but JDBC URLs and configuration values
are not printed.

If the old database is in a different working directory, either start v0.3.0
once from that directory or select it explicitly with
`QUARKUS_DATASOURCE_JDBC_URL`. Supplying an explicit JDBC URL intentionally
disables automatic migration.

## Backup, rollback, and uninstall

Stop the application before making a manual database backup. The safest options
are SQLite's `.backup` command or copying the database together with any `-wal`
and `-shm` sidecar files.

To roll back immediately after automatic migration, stop ccc-usage-dashboard and point
`QUARKUS_DATASOURCE_JDBC_URL` at the retained legacy file. Do not delete either
copy until the selected database has been verified.

Uninstalling the executable or LaunchAgent does not delete user state. To remove
all v0.3 state, stop the application, make any required backup, and delete
`~/.ccc-usage-dashboard`. A legacy working-directory database is outside this
directory and must be removed separately.
