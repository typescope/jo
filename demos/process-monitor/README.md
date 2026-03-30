# Process Monitor

This example demonstrates how platforms can provide system capabilities through **context parameters**. The monitor checks CPU load and memory usage on a fixed interval and invokes a user-defined alert script when usage exceeds preset thresholds.

## Architecture

```
┌─────────────────────┐
│   UserApp.jo        │  User code (untrusted)
│ (Process Monitor)   │  - receives process, system, logger, alerter
└──────────┬──────────┘  - uses context params only
           │ receives
           ▼
┌─────────────────────┐
│  PlatformAPI.jo     │  Pure world (interfaces)
│                     │  - interface Process
│  param process      │  - interface System   (+ CPU load, memory)
│  param system       │  - interface Logger
│  param logger       │  - interface Timer
│  param timer        │  - interface Alerter
│  param alerter      │  - param declarations
└──────────┬──────────┘
           │ provided by
           ▼
┌─────────────────────┐
│ PlatformRuntime.jo  │  Runtime world (trusted)
│                     │  - ProcessImpl
│  platformMain       │  - SystemImpl    (cpuLoadAvg, freeMemoryMB, totalMemoryMB)
│                     │  - LoggerImpl
│                     │  - TimerImpl     (blocking sleep via time.sleep)
└──────────┬──────────┘  - AlerterImpl   (runs user-defined alert script)
           │ uses
           ▼
┌─────────────────────┐
│  py.python          │  Base Python runtime
│  (py intrinsic)     │  - Python interop
└─────────────────────┘
```

## Capabilities

| Capability | Interface method | Runtime implementation |
|---|---|---|
| Periodic timer | `Timer.wait(ms)` | `time.sleep(secs)` |
| CPU metric | `System.cpuLoadAvg()` | `os.getloadavg()[0]` |
| Memory metrics | `System.freeMemoryMB()` / `totalMemoryMB()` | `/proc/meminfo` (Linux) / `vm_stat` (macOS) |
| Alert action | `Alerter.do(subject, body)` | Runs `ALERT_SCRIPT` with subject and body as arguments |

## Files

### PlatformAPI.jo

Declares capability interfaces and context parameters:

```jo
interface Alerter
  def do(subject: String, body: String): Unit
end

param timer: Timer
param alerter: Alerter

defer def startMonitor(intervalSecs: Int): Unit receives stdout, process, system, logger, timer, alerter
```

### PlatformRuntime.jo

**`AlerterImpl`** — runs a configurable shell script with the alert subject and body:

```jo
class AlerterImpl(scriptPath: String)
  def do(subject: String, body: String): Unit =
    val path = scriptPath
    python "__import__('subprocess').run([path, subject, body], capture_output=True)"
  view SystemAPI.Alerter
end
```

The script path is read from the environment in `platformMain`:

```jo
val alertScript : String = python "__import__('os').environ.get('ALERT_SCRIPT', './alert.sh')"
val alerterImpl  = new AlerterImpl(alertScript)
```

### UserApp.jo

User code calls `alerter.do` and never touches the script path or any credentials:

```jo
private def checkAndAlert(): Unit receives stdout, process, system, logger, alerter =
  val cpuLoad = system.cpuLoadAvg()
  val memPct  = (system.totalMemoryMB() - system.freeMemoryMB()) * 100 / system.totalMemoryMB()

  if cpuLoad > 200 then
    alerter.do "[Alert] High CPU Load" ("CPU load avg: " + cpuLoadStr)
  end
  if memPct > 85 then
    alerter.do "[Alert] High Memory Usage" ("Memory: " + memPct + "%")
  end
```

## Setup

### Alert script

Create an executable script that receives the alert subject as `$1` and body as `$2`. It can do anything — send email, post to a webhook, page on-call, etc.

**Example: Slack webhook**
```bash
#!/bin/sh
curl -s -X POST "$SLACK_WEBHOOK_URL" \
  -H 'Content-Type: application/json' \
  -d "{\"text\": \"*$1*\n$2\"}"
```

**Example: send email via sendmail**
```bash
#!/bin/sh
echo "Subject: $1\n\n$2" | sendmail oncall@example.com
```

Make the script executable and point `ALERT_SCRIPT` at it:

```bash
chmod +x alert.sh
export ALERT_SCRIPT="./alert.sh"
```

### Running

```bash
export ALERT_SCRIPT="./alert.sh"
export MONITOR_INTERVAL_SECS=30   # optional, default 30
demos/process-monitor/build.sh
```

If `ALERT_SCRIPT` is not set, the runtime defaults to `./alert.sh`. If the script is missing or not executable, alerts are silently skipped.

## Compilation

### Stage 1 — Platform API
```bash
bin/jo build-lib PlatformAPI.jo -d out/api
```

### Stage 2 — Platform Runtime
```bash
bin/jo build-lib PlatformRuntime.jo \
  -lib out/api \
  -d out/runtime
```

### Stage 3 — User Application
```bash
bin/pyc \
  -link jo.main=SystemRuntime.platformMain \
  -lib out/api \
  -runtime out/runtime \
  UserApp.jo \
  -o out/monitor.py
```

## Security Properties

Context parameters provide strong security guarantees:

1. **User code cannot access undeclared capabilities** — only `process`, `system`, `logger`, and `alerter` are exposed; the user never sees the script path or any credentials
2. **Platform controls the check interval** — user code has no way to change the 30 s period
3. **Type-safe capability access** — enforced at compile time
4. **Alert mechanism is fully swappable** — the platform wires in any `Alerter` implementation; user code is oblivious to whether alerts go to email, Slack, PagerDuty, or anywhere else
