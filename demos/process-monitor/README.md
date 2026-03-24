# Process Monitor

This example demonstrates how platforms can provide system capabilities through **context parameters**. The monitor checks CPU load and memory usage on a fixed interval and sends Gmail alert emails when usage exceeds preset thresholds.

## Architecture

```
┌─────────────────────┐
│   UserApp.jo        │  User code (untrusted)
│ (Process Monitor)   │  - receives process, system, logger, mailer
└──────────┬──────────┘  - uses context params only
           │ receives
           ▼
┌─────────────────────┐
│  PlatformAPI.jo     │  Pure world (interfaces)
│                     │  - interface Process
│  param process      │  - interface System   (+ CPU load, memory)
│  param system       │  - interface Logger
│  param logger       │  - interface Timer
│  param timer        │  - interface Mailer
│  param mailer       │  - param declarations
└──────────┬──────────┘
           │ provided by
           ▼
┌─────────────────────┐
│ PlatformRuntime.jo  │  Runtime world (trusted)
│                     │  - ProcessImpl
│  platformMain       │  - SystemImpl    (os.loadavg, freemem, totalmem)
│                     │  - LoggerImpl
│                     │  - TimerImpl     (blocking sleep via Unix sleep)
└──────────┬──────────┘  - MailerImpl    (Gmail REST API via curl)
           │ uses
           ▼
┌─────────────────────┐
│  js.javascript      │  Base JS runtime
│  (js intrinsic)     │  - Node.js interop
└─────────────────────┘
```

## New capabilities vs. original demo

| Capability | Interface method | Runtime implementation |
|---|---|---|
| Periodic timer | `Timer.wait(ms)` | `child_process.execSync('sleep N')` |
| CPU metric | `System.cpuLoadAvg()` | `os.loadavg()[0]` |
| Memory metric | `System.freeMemoryMB()` / `totalMemoryMB()` | `os.freemem()` / `os.totalmem()` |
| Alert email | `Mailer.sendAlert(subject, body)` | Gmail REST API via `curl` + `spawnSync` |

## Files

### PlatformAPI.jo

Declares capability interfaces and context parameters:

```jo
interface Timer
  def wait(ms: Int): Unit
end

interface Mailer
  def sendAlert(subject: String, body: String): Unit
end

param timer: Timer
param mailer: Mailer
```

The `Monitor` section drives the loop — the platform controls the interval:

```jo
section Monitor
  defer def checkAndAlert(): Unit receives stdout, process, system, logger, mailer

  def startMonitor(intervalMs: Int): Unit receives stdout, process, system, logger, timer, mailer =
    // ... print system info ...
    while true do
      checkAndAlert()
      timer.wait(intervalMs)
    end
end
```

### PlatformRuntime.jo

**`TimerImpl`** — blocking sleep using Unix `sleep`:
```jo
class TimerImpl
  def wait(ms: Int): Unit =
    val secs = js "Math.max(1, Math.round(ms / 1000))"
    js "require('child_process').execSync('sleep ' + secs)"
  view SystemAPI.Timer
end
```

**`MailerImpl`** — Gmail REST API via curl:
```jo
class MailerImpl(accessToken: String, recipient: String)
  def sendAlert(subject: String, body: String): Unit =
    val token = accessToken
    val to = recipient
    val nl = "\n"
    val rawEmail = "To: " + to + nl + "Subject: " + subject + nl + nl + body
    val encoded = js "Buffer.from(rawEmail).toString('base64').replace(/\\+/g,'-')..."
    val payload = js "JSON.stringify({ raw: encoded })"
    js "require('child_process').spawnSync('curl', ['-s', '-X', 'POST',
         'https://gmail.googleapis.com/gmail/v1/users/me/messages/send',
         '-H', 'Authorization: Bearer ' + token, ...])"
  view SystemAPI.Mailer
end
```

Credentials are read from environment variables in `platformMain`:
```jo
val gmailToken = js "process.env.GMAIL_ACCESS_TOKEN || ''"
val alertEmail = js "process.env.ALERT_EMAIL_RECIPIENT || ''"
val mailerImpl = new MailerImpl(gmailToken, alertEmail)
```

### UserApp.jo

User code implements `checkAndAlert` and never touches credentials or timers directly:

```jo
def checkAndAlert(): Unit receives stdout, process, system, logger, mailer =
  val cpuLoad = system.cpuLoadAvg()
  val memPct  = (system.totalMemoryMB() - system.freeMemoryMB()) * 100 / system.totalMemoryMB()

  if cpuLoad > 2.0 then
    mailer.sendAlert "[Alert] High CPU Load" ("CPU load: " + cpuLoad.toString)
  end
  if memPct > 85 then
    mailer.sendAlert "[Alert] High Memory Usage" ("Memory: " + memPct + "%")
  end
```

## Setup

### Gmail access token

The simplest way to get a short-lived token for testing:

```bash
# Requires gcloud CLI authenticated with a Google account that has Gmail API enabled
export GMAIL_ACCESS_TOKEN=$(gcloud auth print-access-token)
export ALERT_EMAIL_RECIPIENT="you@example.com"
```

For longer-lived tokens, use the [Google OAuth 2.0 Playground](https://developers.google.com/oauthplayground) with the `https://www.googleapis.com/auth/gmail.send` scope.

### Running

```bash
export GMAIL_ACCESS_TOKEN="ya29...."
export ALERT_EMAIL_RECIPIENT="oncall@example.com"
demos/process-monitor/build.sh
```

Without the env vars the monitor still runs; alert emails are silently skipped.

## Compilation

### Stage 1 — Platform API
```bash
bin/jo build-lib PlatformAPI.jo -d out/api
```

### Stage 2 — Platform Runtime
```bash
bin/jo build-lib PlatformRuntime.jo \
  -lib libs/runtime-js:out/api \
  -d out/runtime
```

### Stage 3 — User Application
```bash
bin/jo build -js \
  -link jo.main=SystemRuntime.platformMain \
  -link SystemAPI.Monitor.checkAndAlert=ProcessMonitor.Analysis.checkAndAlert \
  -lib out/api \
  -runtime out/runtime \
  UserApp.jo \
  -o out/monitor.js
```

## Security Properties

Context parameters provide strong security guarantees:

1. **User code cannot access undeclared capabilities** — only `process`, `system`, `logger`, `mailer` are exposed; the user never sees the Gmail token or the `timer`
2. **Platform controls the check interval** — user code has no way to change the 30 s period
3. **Type-safe capability access** — enforced at compile time
4. **Credentials stay in the runtime layer** — `accessToken` is a constructor arg on `MailerImpl`, invisible to user code
