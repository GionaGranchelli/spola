# Spola — Guida al Test Manuale

Tutto parte da `./gradlew build` per compilare, poi usi `./gradlew :spola-backend-cli:run --args="..."` per eseguire.

---

## 1. Test unitari (sempre il primo passo)

```bash
cd ~/Development/spola && ./gradlew :spola-backend-core:test
```

Esce: **207 test, 0 failures** — se fallisce, qualcosa è rotto.

---

## 2. One-Shot Agent (modalità base)

```zsh
export OPENAI_API_KEY="sk-..."
./gradlew :spola-backend-cli:run --args="'scrivi un file README.md in /tmp che spiega Spola'"
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
./gradlew :spola-backend-cli:installDist
./spola-backend-cli/build/install/spola/bin/spola
```

Vedrai il prompt `spola> `. Comandi:

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
./gradlew :spola-backend-cli:run --args="--mcp --mcp-transport sse --mcp-port 8091"
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
./gradlew :spola-backend-cli:run --args="--mcp --mcp-transport stdio"
```

**Cosa testa:** MCP server, JSON-RPC, tool → schema mapping, stdio/SSE transport, auth (se `--api-key` è impostato)

---

## 5. REST API Server

```bash
# Terminal 1: avvia API server
export OPENAI_API_KEY="sk-..."
./gradlew :spola-backend-cli:run --args="--api --api-port 8082"
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
./gradlew :spola-backend-cli:run --args="--daemon --scheduler-db ./.spola/scheduler.db"
```

Da un altro terminal:

```bash
# Aggiungi job via CLI (spolamo scheduler add)
./gradlew :spola-backend-cli:run --args="scheduler add --name test --cron '* * * * *' 'echo ciao'"

# Lista job
./gradlew :spola-backend-cli:run --args="scheduler list"

# Rimuovi job
./gradlew :spola-backend-cli:run --args="scheduler remove <job-id>"
```

**Cosa testa:** cron parsing, job store SQLite, polling loop, esecuzione job

---

## 7. Auth (API key)

```bash
# Avvia con api-key
export OPENAI_API_KEY="sk-..."
./gradlew :spola-backend-cli:run --args="--api --api-port 8082 --api-key secret123"

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
# HELP spola_agent_runs_total Total number of agent runs.
# TYPE spola_agent_runs_total counter
spola_agent_runs_total{status="success"} 1
spola_agent_runs_total{status="fail"} 0
spola_agent_turns_total 3.0
spola_tool_calls_total{tool="shell",status="success"} 2.0
...
```

**Cosa testa:** tutte le 9 metriche sono presenti, formato Prometheus valido

---

## 9. Docker

```bash
cd ~/Development/spola

# Build immagine
docker compose build

# Avvia
docker compose up

# Test
curl http://127.0.0.1:8082/api/health
```

Il docker-compose avvia due servizi:
- `spola-backend-api` su `127.0.0.1:8082`
- `spola-backend-mcp` su `127.0.0.1:8091`

**Cosa testa:** containerizzazione, non-root user, env var per API key, healthcheck, port binding sicuro

---

## 10. Voice/TTS Tool

Dentro la REPL o one-shot:

```bash
# Con ElevenLabs (richiede ELEVENLABS_API_KEY)
export ELEVENLABS_API_KEY="..."
./gradlew :spola-backend-cli:run --args="'usa tts_say per dire Ciao mondo'"

# Con Edge TTS (gratis, edge-tts in PATH)
./gradlew :spola-backend-cli:run --args="'usa tts_say per dire Ciao mondo'"
```

Il tool produce file audio in `~/.spola/audio/`.

**Cosa testa:** TTS provider selection, ElevenLabs API, Edge CLI fallback, file caching

---

## 11. Checkpoint / Resume

```bash
# Avvia API server
export OPENAI_API_KEY="sk-..."
./gradlew :spola-backend-cli:run --args="--api --api-port 8082"

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

Prima di avviare l'app, assicurati che Spola API server sia in esecuzione (`--api --api-port 8082`).

---

## 13. Plugin System

```bash
# Crea un JAR plugin (esempio)
# Metti il JAR in ~/.spola/plugins/
mkdir -p ~/.spola/plugins
# (metti il tuo plugin.jar qui)

# Avvia Spola — carica automaticamente tutti i plugin
export OPENAI_API_KEY="sk-..."
./gradlew :spola-backend-cli:run --args="'listami i tool disponibili'"
```

I plugin devono implementare `SpolaPlugin` e avere `META-INF/services/dev.spola.plugin.SpolaPlugin`.

**Cosa testa:** PluginLoader, ServiceLoader, tool conflict detection, shutdown lifecycle

---

## Cheat Sheet Riepilogativa

```bash
# Build
./gradlew build

# Test
./gradlew :spola-backend-core:test

# One-shot
./gradlew :spola-backend-cli:run --args="'tuo goal qui'"

# REPL
./gradlew :spola-backend-cli:run

# API server
./gradlew :spola-backend-cli:run --args="--api --api-port 8082 --api-key secret"

# MCP SSE
./gradlew :spola-backend-cli:run --args="--mcp --mcp-transport sse --mcp-port 8091"

# MCP stdio
./gradlew :spola-backend-cli:run --args="--mcp --mcp-transport stdio"

# Scheduler daemon
./gradlew :spola-backend-cli:run --args="--daemon --scheduler-db ./.spola/scheduler.db"

# Scheduler admin
./gradlew :spola-backend-cli:run --args="scheduler add --name test --cron '0 */2 * * *' 'fai qualcosa'"
./gradlew :spola-backend-cli:run --args="scheduler list"
./gradlew :spola-backend-cli:run --args="scheduler remove <id>"

# Docker
docker compose up --build

# Frontend
cd ~/Development/openclaw-app && ./gradlew composeApp:run
```
