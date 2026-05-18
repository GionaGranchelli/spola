# Golem — Guida al Test Manuale

Tutto parte da `./gradlew build` per compilare, poi usi `./gradlew :golem-cli:run --args="..."` per eseguire.

---

## 1. Test unitari (sempre il primo passo)

```bash
cd ~/Development/golem && ./gradlew :golem-core:test
```

Esce: **207 test, 0 failures** — se fallisce, qualcosa è rotto.

---

## 2. One-Shot Agent (modalità base)

```zsh
export OPENAI_API_KEY="sk-..."
./gradlew :golem-cli:run --args="'scrivi un file README.md in /tmp che spiega Golem'"
```

L'agent:
1. Carica il prompt + la persona
2. Fa il ReAct loop: LLM → decide tool → esegue → feedback → ripete
3. Produce il file `/tmp/README.md`
4. Stampa il risultato ed esce

**Cosa testa:** file read/write, shell, ReAct loop, persona loader, tool calling funziona end-to-end

---

## 3. REPL (modalità interattiva)

Gradle non supporta REPL interattivo. Devi prima buildare la distribuzione:

```bash
export OPENAI_API_KEY="sk-..."
./gradlew :golem-cli:installDist
./golem-cli/build/install/golem-cli/bin/golem-cli
```

Vedrai il prompt `golem> `. Comandi:

| Comando | Cosa fa |
|---------|---------|
| `scrivi hello.txt con contenuto ciao` | Esegue come goal |
| `/tools` | Elenca tutti i 25 tool registrati |
| `/memory` | Mostra i fatti salvati in memoria |
| `/persona` | Mostra la persona corrente |
| `/help` | Lista comandi REPL |
| `/exit` | Esce |

**Cosa testa:** REPL interaction, /commands, session state

---

## 4. MCP Server (integra con Claude Code / Codex)

```bash
# Terminal 1: avvia MCP in modalità SSE
export OPENAI_API_KEY="sk-..."
./gradlew :golem-cli:run --args="--mcp --mcp-transport sse --mcp-port 8091"
```

Poi da un altro terminal:

```bash
# Test con curl (SSE handshake)
curl -N http://localhost:8091/sse

# Test tools/list
curl -X POST http://localhost:8091/sse \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

**Modalità stdio** (per Claude Code):
```bash
export OPENAI_API_KEY="sk-..."
./gradlew :golem-cli:run --args="--mcp --mcp-transport stdio"
```

**Cosa testa:** MCP server, JSON-RPC, tool → schema mapping, stdio/SSE transport, auth (se `--api-key` è impostato)

---

## 5. REST API Server

```bash
# Terminal 1: avvia API server
export OPENAI_API_KEY="sk-..."
./gradlew :golem-cli:run --args="--api --api-port 8082"
```

Da un altro terminal:

```bash
# Health check (nessun auth richiesto)
curl http://localhost:8082/api/health

# Status agent
curl -H 'Authorization: Bearer mio-segreto' \
  http://localhost:8082/api/agent/status

# Lista tools
curl -H 'Authorization: Bearer mio-segreto' \
  http://localhost:8082/api/tools

# Esegui agent (one-shot via API)
curl -X POST http://localhost:8082/api/agent/run \
  -H 'Authorization: Bearer mio-segreto' \
  -H 'Content-Type: application/json' \
  -d '{"goal":"dimmi che ore sono"}'

# SSE streaming
curl -N -X POST http://localhost:8082/api/agent/run/stream \
  -H 'Authorization: Bearer mio-segreto' \
  -H 'Content-Type: application/json' \
  -d '{"goal":"conta fino a 3"}'

# Prometheus metrics (nessun auth)
curl http://localhost:8082/api/metrics

# Memory
curl -H 'Authorization: Bearer mio-segreto' \
  http://localhost:8082/api/memory

# Scheduled jobs
curl -H 'Authorization: Bearer mio-segreto' \
  http://localhost:8082/api/jobs

# Crea job
curl -X POST http://localhost:8082/api/jobs \
  -H 'Authorization: Bearer mio-segreto' \
  -H 'Content-Type: application/json' \
  -d '{"name":"saluto","goal":"echo ciao","cronExpression":"0 0 * * *","enabled":true}'
```

**Cosa testa:** Ktor server, auth Bearer, tutti gli endpoint, SSE streaming, jobs CRUD, memory, metrics

---

## 6. Scheduler (cron jobs)

```bash
# Terminal 1: avvia scheduler daemon
export OPENAI_API_KEY="sk-..."
./gradlew :golem-cli:run --args="--daemon --scheduler-db ./.golem/scheduler.db"
```

Da un altro terminal:

```bash
# Aggiungi job via CLI (golemmo scheduler add)
./gradlew :golem-cli:run --args="scheduler add --name test --cron '* * * * *' 'echo ciao'"

# Lista job
./gradlew :golem-cli:run --args="scheduler list"

# Rimuovi job
./gradlew :golem-cli:run --args="scheduler remove <job-id>"
```

**Cosa testa:** cron parsing, job store SQLite, polling loop, esecuzione job

---

## 7. Auth (API key)

```bash
# Avvia con api-key
export OPENAI_API_KEY="sk-..."
./gradlew :golem-cli:run --args="--api --api-port 8082 --api-key secret123"

# Senza chiave → 401
curl http://localhost:8082/api/agent/status

# Con chiave sbagliata → 403
curl -H 'Authorization: Bearer wrong' http://localhost:8082/api/agent/status

# Con chiave giusta → 200
curl -H 'Authorization: Bearer secret123' http://localhost:8082/api/agent/status

# /api/metrics non richiede auth (anche con api-key impostata)
curl http://localhost:8082/api/metrics   # → 200
```

**Cosa testa:** ApiAuth.kt, Bearer validation, eccezioni 401/403, metrics bypass

---

## 8. Prometheus Metrics

Mentre l'API server è in esecuzione:

```bash
curl http://localhost:8082/api/metrics
```

Output:
```
# HELP golem_agent_runs_total Total number of agent runs.
# TYPE golem_agent_runs_total counter
golem_agent_runs_total{status="success"} 1
golem_agent_runs_total{status="fail"} 0
golem_agent_turns_total 3.0
golem_tool_calls_total{tool="shell",status="success"} 2.0
...
```

**Cosa testa:** tutte le 9 metriche sono presenti, formato Prometheus valido

---

## 9. Docker

```bash
cd ~/Development/golem

# Build immagine
docker compose build

# Avvia
docker compose up

# Test
curl http://127.0.0.1:8082/api/health
```

Il docker-compose avvia due servizi:
- `golem-api` su `127.0.0.1:8082`
- `golem-mcp` su `127.0.0.1:8091`

**Cosa testa:** containerizzazione, non-root user, env var per API key, healthcheck, port binding sicuro

---

## 10. Voice/TTS Tool

Dentro la REPL o one-shot:

```bash
# Con ElevenLabs (richiede ELEVENLABS_API_KEY)
export ELEVENLABS_API_KEY="..."
./gradlew :golem-cli:run --args="'usa tts_say per dire Ciao mondo'"

# Con Edge TTS (gratis, edge-tts in PATH)
./gradlew :golem-cli:run --args="'usa tts_say per dire Ciao mondo'"
```

Il tool produce file audio in `~/.golem/audio/`.

**Cosa testa:** TTS provider selection, ElevenLabs API, Edge CLI fallback, file caching

---

## 11. Checkpoint / Resume

```bash
# Avvia API server
export OPENAI_API_KEY="sk-..."
./gradlew :golem-cli:run --args="--api --api-port 8082"

# Fai una run
curl -X POST http://localhost:8082/api/agent/run \
  -H 'Content-Type: application/json' \
  -d '{"goal":"esplora la directory /tmp"}'

# Vedi i checkpoint salvati
curl http://localhost:8082/api/checkpoint

# Riprendi una sessione
curl http://localhost:8082/api/checkpoint/resume/<session-id>
```

**Cosa testa:** checkpoint store SQLite, save automatico, resume conversation

---

## 12. openclaw-app Frontend

```bash
cd ~/Development/openclaw-app

# Desktop
./gradlew composeApp:run

# Android
./gradlew composeApp:assembleDebug
```

L'app mostra:
- **Session sidebar** a sinistra — lista sessioni, crea/cancella
- **Model selector** — dropdown per modello per sessione
- **Dashboard** — Tool Browser, Memory Search, Scheduler List
- **Chat pane** — invia goal, vede streaming response

Prima di avviare l'app, assicurati che Golem API server sia in esecuzione (`--api --api-port 8082`).

---

## 13. Plugin System

```bash
# Crea un JAR plugin (esempio)
# Metti il JAR in ~/.golem/plugins/
mkdir -p ~/.golem/plugins
# (metti il tuo plugin.jar qui)

# Avvia Golem — carica automaticamente tutti i plugin
export OPENAI_API_KEY="sk-..."
./gradlew :golem-cli:run --args="'listami i tool disponibili'"
```

I plugin devono implementare `GolemPlugin` e avere `META-INF/services/dev.spola.plugin.GolemPlugin`.

**Cosa testa:** PluginLoader, ServiceLoader, tool conflict detection, shutdown lifecycle

---

## Cheat Sheet Riepilogativa

```bash
# Build
./gradlew build

# Test
./gradlew :golem-core:test

# One-shot
./gradlew :golem-cli:run --args="'tuo goal qui'"

# REPL
./gradlew :golem-cli:run

# API server
./gradlew :golem-cli:run --args="--api --api-port 8082 --api-key secret"

# MCP SSE
./gradlew :golem-cli:run --args="--mcp --mcp-transport sse --mcp-port 8091"

# MCP stdio
./gradlew :golem-cli:run --args="--mcp --mcp-transport stdio"

# Scheduler daemon
./gradlew :golem-cli:run --args="--daemon --scheduler-db ./.golem/scheduler.db"

# Scheduler admin
./gradlew :golem-cli:run --args="scheduler add --name test --cron '0 */2 * * *' 'fai qualcosa'"
./gradlew :golem-cli:run --args="scheduler list"
./gradlew :golem-cli:run --args="scheduler remove <id>"

# Docker
docker compose up --build

# Frontend
cd ~/Development/openclaw-app && ./gradlew composeApp:run
```
