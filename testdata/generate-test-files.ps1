# Regenerates the Day-0 adversarial test files from the charter (§7.2).
# big.log is intentionally NOT committed (400 MB) — run this script to recreate an equivalent ~400 MB file.
param(
    [int]$BigLogLines = 4200000   # ~400 MB with the pattern below
)
$ErrorActionPreference = 'Stop'
$nl = [string][char]13 + [string][char]10

# --- hibernate.log: Hibernate 6 format_sql, multi-line SQL + binding parameter lines ---
$h = @(
'2026-10-01 09:14:02.101 DEBUG [http-nio-8080-exec-4] org.hibernate.SQL - '
'    select'
'        u1_0.id,'
'        u1_0.email,'
'        u1_0.name,'
'        o2_0.user_id,'
'        o2_0.id,'
'        o2_0.total'
'    from'
'        users u1_0 '
'    left join'
'        orders o2_0 on u1_0.id=o2_0.user_id '
'    where'
'        u1_0.email=?'
'2026-10-01 09:14:02.104 TRACE [http-nio-8080-exec-4] org.hibernate.orm.jdbc.bind - binding parameter [1] as [VARCHAR] - [user@example.com]'
'2026-10-01 09:14:02.319 ERROR [http-nio-8080-exec-4] c.e.s.UserService - Failed to load user 4711'
'java.lang.NullPointerException: Cannot invoke "User.getEmail()" because "user" is null'
"	at com.example.service.UserService.sendWelcomeEmail(UserService.java:128)"
'	at com.example.web.UserController.create(UserController.java:64)'
'2026-10-01 09:14:02.320  WARN [scheduling-1] c.e.s.CleanupJob - 3 stale sessions removed'
'2026-10-01 09:14:03.010 DEBUG [http-nio-8080-exec-7] org.hibernate.SQL - '
'    insert'
'    into'
'        orders (user_id, total, id)'
'    values'
'        (?, ?, ?)'
'2026-10-01 09:14:03.011 TRACE [http-nio-8080-exec-7] org.hibernate.orm.jdbc.bind - binding parameter [1] as [BIGINT] - [4711]'
'2026-10-01 09:14:03.012 TRACE [http-nio-8080-exec-7] org.hibernate.orm.jdbc.bind - binding parameter [2] as [NUMERIC] - [129.99]'
'2026-10-01 09:14:03.013 TRACE [http-nio-8080-exec-7] org.hibernate.orm.jdbc.bind - binding parameter [3] as [BIGINT] - [90815]'
) -join $nl
[IO.File]::WriteAllText("$PSScriptRoot\hibernate.log", $h + $nl, [Text.UTF8Encoding]::new($false))

# --- docker.log: real ANSI escapes (16/256/truecolor) in Docker log-prefix format ---
$esc = [string][char]27
$d = @()
$d += "$esc[36mdocker-compose$esc[0m | $esc[90m2026-10-01T09:14:00Z$esc[0m app-1  | $esc[32m[INFO]$esc[0m Server started on port 8080"
$d += "$esc[36mdocker-compose$esc[0m | $esc[90m2026-10-01T09:14:01Z$esc[0m app-1  | $esc[33m[WARN]$esc[0m Connection pool 80% full"
$d += "$esc[36mdocker-compose$esc[0m | $esc[90m2026-10-01T09:14:02Z$esc[0m app-1  | $esc[38;5;196m[ERROR]$esc[0m Query timeout after 30s"
$d += "$esc[36mdocker-compose$esc[0m | $esc[90m2026-10-01T09:14:02Z$esc[0m app-1  | $esc[38;2;255;128;0m[TRUECOLOR]$esc[0m payload rendered rgb(255,128,0)"
$d += "$esc[36mdocker-compose$esc[0m | $esc[90m2026-10-01T09:14:03Z$esc[0m app-1  | $esc[1;31mjava.sql.SQLException: timeout$esc[0m"
$d += "$esc[36mdocker-compose$esc[0m | $esc[90m2026-10-01T09:14:03Z$esc[0m app-1  | $esc[2m	at com.example.db.QueryRunner.run(QueryRunner.java:88)$esc[0m"
$d += "$esc[36mdocker-compose$esc[0m | $esc[90m2026-10-01T09:14:04Z$esc[0m app-2  | $esc[32m[INFO]$esc[0m healthcheck OK"
[IO.File]::WriteAllText("$PSScriptRoot\docker.log", ($d -join $nl) + $nl, [Text.UTF8Encoding]::new($false))

# --- big.log: synthetic Logback log, ERROR + 3-line stack trace every 500 lines ---
if ($BigLogLines -gt 0) {
    $levels = 'DEBUG','INFO','WARN','INFO','DEBUG','ERROR','INFO','DEBUG'
    $loggers = 'c.e.s.UserService','c.e.s.OrderService','o.h.SQL','c.e.web.UserController','c.e.s.CleanupJob'
    $sw = [IO.StreamWriter]::new("$PSScriptRoot\big.log", $false, [Text.UTF8Encoding]::new($false), 1MB)
    try {
        for ($i = 0; $i -lt $BigLogLines; $i++) {
            $h2 = 9 + [math]::Floor($i / 60000); $m2 = [math]::Floor(($i % 60000) / 1000); $s2 = $i % 60
            $ts = '2026-10-01 {0:d2}:{1:d2}:{2:d2}.000' -f $h2, $m2, $s2
            $lvl = $levels[$i % 8]; $lg = $loggers[$i % 5]; $th = 'http-nio-8080-exec-' + (($i % 16) + 1)
            $sw.WriteLine("$ts $lvl [$th] $lg - Request $i processed in $($i % 40)ms")
            if ($lvl -eq 'ERROR') {
                $sw.WriteLine("java.lang.NullPointerException: Cannot invoke ""User.getEmail()"" because ""user"" is null")
                $sw.WriteLine('	at com.example.service.UserService.sendWelcomeEmail(UserService.java:128)')
                $sw.WriteLine('	at com.example.web.UserController.create(UserController.java:64)')
            }
        }
    } finally { $sw.Dispose() }
    Write-Host "big.log: $((Get-Item "$PSScriptRoot\big.log").Length) bytes"
}
Write-Host 'Done: hibernate.log, docker.log' + $(if ($BigLogLines -gt 0) { ', big.log' } else { '' })
