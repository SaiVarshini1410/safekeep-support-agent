# SafeKeep Support Agent

An event-driven AI agent that investigates incidents on an enterprise VM backup platform.

When a monitored application reports a problem (high memory, a failed backup job, an unreachable dependency), the Agent opens an investigation, gathers evidence through permitted diagnostic tools, separates **confirmed facts** from **suspected causes**, recommends next steps, and proposes corrective actions that run **only after a person with the right role approves them**. Every step is recorded.

> **Core design idea: the AI reasons; code enforces.**
> The model only chooses which diagnostic tool to call and writes the analysis. Permissions, the approval gate, state transitions, evidence checks and outcome verification are deterministic Java.

All product names, hosts and data are fictional.

---

## Quick start

### Prerequisites
| Tool | Version |
|---|---|
| Java | 21 |
| Ollama | any recent version |
| Model | `qwen2.5:3b` (about 2 GB) |

Maven is not needed; the project includes the Maven wrapper (`./mvnw`).

### Run
```bash
brew services start ollama        # or: ollama serve
ollama pull qwen2.5:3b
./run.sh                          # sets demo API keys and starts the app
```
Open **http://localhost:8080**.

| User | Password | Role | Can |
|---|---|---|---|
| `alice` | `demo` | Application user | View, ask questions, add context, give feedback |
| `sam` | `demo` | Support engineer | All of the above, approve low-risk actions, view health |
| `adam` | `demo` | Administrator | All of the above, approve high-risk actions, enable/disable tools and actions |

To start with an empty database: `rm -rf data` before `./run.sh`.

### Demo in five steps
1. Sign in as **sam** → **Event Simulator** → **S-1A** → **Send event**.
2. Open the investigation from the toast and watch the live timeline (2–4 minutes on a laptop).
3. Read the findings (confirmed vs suspected) and click an evidence chip to see the raw tool result.
4. Click **Approve and run**. The action executes, the system re-checks memory, and the resolution becomes **Resolved**.
5. Sign in as **alice**: the same proposal shows "Requires Support engineer approval" and no buttons.

---

## Scenarios

| ID | Application | Event | Root cause hidden in the data | What it demonstrates |
|---|---|---|---|---|
| S-1A | Backup Server | HIGH_MEMORY | 1,172 stale job sessions; cleanup stuck since an upgrade | Approved action succeeds and is verified |
| S-1B | Backup Server | HIGH_CPU | New authentication session on every job step | Temporary fix, recurrence warning |
| S-1C | Storage Server | HIGH_STORAGE_USAGE | Expiration not run for 9 days | Admin-only action; partially resolved |
| S-2A | Backup Server | JOB_FAILED | Storage target full | Retry approved, **fails**, reported honestly |
| S-2C | Backup Server | JOB_FAILED | Not enough evidence | Agent says what is missing instead of guessing |
| S-2D | Backup Server | REPORT_JOB_FAILED | Report query timeout | Low-risk action an application user may approve |
| S-2E | Storage Server | REPLICATION_JOB_FAILED | Network throughput drop | Escalation |
| S-3A | Backup Server | DATASOURCE_UNHEALTHY | vCenter certificate expired | Affected vs unaffected operations; escalation |
| S-3B | Backup Server | DEPENDENCY_UNAVAILABLE | Storage server down | Impact on backups and restores |
| S-4A | Storage Server | UNEXPECTED_RESTART | Kernel panic after disk I/O errors | Hardware cause; no action |

Seed data contains only raw metrics, logs and records, never the answer. The model has to infer the cause.

---

## Architecture

```
Simulator / any app ──POST /api/events (API key)──▶ EventIntakeService ── validate, dedupe, create/link
                                                          │
                                           background worker (4 threads)
                                                          ▼
                                                  AgentOrchestrator ◀──▶ Ollama (qwen2.5:3b)
                                                          │
                                                     ToolGateway ── exists? enabled? valid args? timeout ──▶ App adapter
                                                          │
                                                 ResponseValidator ── evidence checks, downgrades, rules R-V1..R-V8
                                                          │
                                     findings + proposal saved ── user approves ──▶ ApprovalService
                                                                                    role check → run → verify → resolution
```

| Package | Responsibility |
|---|---|
| `intake` | Event validation, idempotency, correlation, create-or-link |
| `investigation` | Investigation entity, state machine, evidence, messages, background scheduling |
| `agent` | Tool-calling loop, prompts, output validation, follow-up questions |
| `llm` | Ollama client behind an `LlmClient` interface |
| `tools` | The single gateway for every tool and action call |
| `apps` | Application configuration and simulated adapters |
| `approval` | Approval gate and outcome verification |
| `security` | Users, roles, API keys, CSRF |
| `audit` | Append-only audit trail |
| `api`, `web` | REST API and server-rendered pages |
| `health` | Health checks and PRD success metrics |
| `simulator` | Plays the monitored applications in the demo |

### Safety guarantees
| Guarantee | Enforced by |
|---|---|
| The AI cannot execute actions | Actions are never offered as tools; only `ApprovalService` can run them, after a role check |
| Suspicions are not shown as facts | A CONFIRMED finding must cite successful evidence, or it is downgraded to SUSPECTED |
| Failures are never reported as success | After an action, the system re-reads state and evaluates a configured condition |
| Only permitted tools and arguments | `ToolGateway`: existence, enablement, argument schema, timeout, recording |
| Secrets stay secret | API keys are masked in logs, errors and stored payloads, and never sent to the model |

---

## Sending an event directly
```bash
curl -X POST localhost:8080/api/events \
  -H "X-Api-Key: demo-backup-key" -H "X-Demo-Scenario: S-1A" \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt-001","application":"backup-server","eventType":"HIGH_MEMORY",
       "resource":"backup-server-01","timestamp":"2026-11-09T10:00:00Z",
       "details":{"memoryPercent":93,"threshold":85}}'
```
Helper scripts: `./send.sh S-1A`, `./show.sh 101`, `./approve.sh 101 1 sam`, `./reject.sh 101 1 sam "reason"`.

---

## Tests
```bash
./mvnw test        # 145 tests; no Ollama or network needed
```
Tests use a scripted `FakeLlmClient`, an in-memory database and a fixed clock. Every test name starts with its test case ID from the Unit-Test Plan (for example `TC-APR-07`), and all 47 requirements are traced to at least one test.

---

## Configuration
| What | Where |
|---|---|
| Model, limits, timeouts | `src/main/resources/application.yml` (`safekeep.agent.*`) |
| Applications: event types, tools, actions, roles, thresholds | `src/main/resources/apps/*.yml` |
| Seed data per scenario | `src/main/resources/seed/<app>/` |
| Runbooks | `src/main/resources/runbooks/<app>/` |
| API keys | Environment variables `BACKUP_SERVER_API_KEY`, `STORAGE_SERVER_API_KEY` (see `.env.example`) |

Adding an application means adding a YAML file and an adapter; no core class changes (proven by test `TC-CFG-04`).

---

## Documents
| Document | Content |
|---|---|
| `docs/SafeKeep_Functional_Spec.docx` | Requirements, decisions, architecture, data model, agent loop, API, UI, security |
| `docs/SafeKeep_Unit_Test_Plan.docx` | 95 test cases, traceability matrix, execution result |
| `docs/SafeKeep_Solution_Overview_and_AI_Usage.docx` | How AI was used, example prompts, corrections and refinements, validation |

PDF versions are in the same folder.

---

## Known limitations
- Applications are simulated; real integrations would replace the adapters.
- A 3B model on a laptop takes about 2–4 minutes per investigation.
- Admin toggles are held in memory and reset on restart.
- Three seeded users instead of SSO.
