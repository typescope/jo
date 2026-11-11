# Process Monitor

[![GitHub](https://img.shields.io/badge/GitHub-demos%2Fprocess--monitor-0969da?logo=github&logoColor=white)](https://github.com/typescope/jo/tree/main/demos/process-monitor)

This example demonstrates how platforms can provide system capabilities through **context parameters**. The monitor checks CPU load and memory usage on a fixed interval and invokes a user-defined alert script when usage exceeds preset thresholds.

The platform ships one factory per capability. The build binds the factories to the context parameters of the API with `--provide`, and binds the alert script path with `--internal`, so the compiler creates the capabilities and calls the user's `main` with them in scope. No platform entry point and no `--link` are needed.

## Architecture

```
┌─────────────────────┐
│   UserApp.jo        │  User code (untrusted)
│ (Process Monitor)   │  - main receives process, system, logger, timer,
└──────────┬──────────┘    alerter, interval; uses context params only
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
│  param interval     │
└──────────┬──────────┘
           │ bound by --provide / --internal
           ▼
┌─────────────────────┐
│ PlatformRuntime.jo  │  Runtime world (trusted)
│                     │  - ProcessImpl
│  factories:         │  - SystemImpl    (cpuLoadAvg, freeMemoryMB, totalMemoryMB)
│  Process, System,   │  - LoggerImpl
│  Logger, Timer,     │  - TimerImpl     (blocking sleep via time.sleep)
│  Alerter, Interval  │  - AlerterImpl   (runs user-defined alert script)
│  param alertScript  │
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
| Alert action | `Alerter.trigger(subject, body)` | Runs `./alert.sh` with subject and body as arguments |

## Files

### PlatformAPI.jo

Declares capability interfaces and context parameters:

```jo
interface Alerter
  def trigger(subject: String, body: String): Unit
end

param timer: Timer
param alerter: Alerter

// Seconds between two health checks; 0 runs a single check
param interval: Int
```

### PlatformRuntime.jo

**`AlerterImpl`** — runs the alert script with the alert subject and body:

```jo
class AlerterImpl(scriptPath: String)
  def trigger(subject: String, body: String): Unit =
    if os.path.exists(scriptPath) then
      val _ = subprocess.run(scriptPath, subject, body)

  view SystemAPI.Alerter
end
```

A factory per capability creates the implementations. The script path is a context parameter of the runtime namespace, which user code cannot name, and the interval comes from the environment:

```jo
param alertScript: String

def Logger(): SystemAPI.Logger receives stdout = new LoggerImpl(stdout)
def Alerter(): SystemAPI.Alerter receives alertScript = new AlerterImpl(alertScript)
def Interval(): Int = os.environ.get("MONITOR_INTERVAL_SECS", "30").toInt
```

### UserApp.jo

The entry point receives the capabilities and nothing else. It calls `alerter.trigger` and never touches the script path or any credentials:

```jo
def main: Unit receives stdout, process, system, logger, timer, alerter, interval =
  ...

private def checkAndAlert(): Unit receives stdout, process, system, logger, alerter =
  val cpuLoad = system.cpuLoadAvg()
  val memPct  = (system.totalMemoryMB() - system.freeMemoryMB()) * 100 / system.totalMemoryMB()

  if cpuLoad > 200 then
    alerter.trigger "[Alert] High CPU Load" ("CPU load avg: " + cpuLoadStr)
  end
  if memPct > 85 then
    alerter.trigger "[Alert] High Memory Usage" ("Memory: " + memPct + "%")
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

Make the script executable:

```bash
chmod +x alert.sh
```

### Running

```bash
export MONITOR_INTERVAL_SECS=30   # optional, default 30
demos/process-monitor/build.sh
```

If `./alert.sh` is missing or not executable, alerts are silently skipped.

## Compilation

### Stage 1 — Platform API
```bash
bin/jo compile --sast out/api PlatformAPI.jo
```

### Stage 2 — Platform Runtime
```bash
bin/jo compile --sast out/runtime --use-runtime-api python \
  os.jo subprocess.jo time.jo PlatformRuntime.jo \
  --lib out/api
```

### Stage 3 — User Application
```bash
bin/jo compile --python \
  --lib out/api \
  --link-lib out/runtime \
  --provide SystemAPI.process=SystemRuntime.Process \
  --provide SystemAPI.system=SystemRuntime.System \
  --provide SystemAPI.logger=SystemRuntime.Logger \
  --provide SystemAPI.timer=SystemRuntime.Timer \
  --provide SystemAPI.alerter=SystemRuntime.Alerter \
  --provide SystemAPI.interval=SystemRuntime.Interval \
  --internal 'SystemRuntime.alertScript="./alert.sh"' \
  UserApp.jo \
  -o out/monitor.py
```

Each `--provide` binds a context parameter to a factory in the runtime library. The compiler calls the factories before `main`, in dependency order, and runs `main` with the results in scope. The `--internal` binding is visible to the factories only: `main` may not receive `SystemRuntime.alertScript`, and the runtime library is a link library, so user code cannot name it or call a factory directly.

## Security Properties

Context parameters provide strong security guarantees:

1. **User code cannot access undeclared capabilities** — only the provided parameters are exposed. The script path is an internal binding, so the user never sees it or any credentials
2. **Platform controls the check interval** — the `Interval` factory reads it from the environment of the platform, not from user code
3. **Type-safe capability access** — enforced at compile time
4. **Alert mechanism is fully swappable** — the platform wires in any `Alerter` implementation. User code is oblivious to whether alerts go to email, Slack, PagerDuty, or anywhere else
