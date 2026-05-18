# Improvement Proposal for OpenClaw App

## Objective

This document ranks the top 5 features that should be implemented next, adds codebase advice to improve development efficiency, and outlines an idea for collaboration between multiple OpenClaw instances across different machines.

---

## Top 5 Features to Implement

### 1. Real session management

**Why it matters:**
This is the foundation of the product. Without real sessions, everything feels like a prototype.

**Implement:**
- session list UI
- create/select/archive/delete session
- persist active session
- load session state on app start

**Outcome:**
A true session-centric client instead of a single implicit workspace.

---

### 2. Per-session model and provider persistence

**Why it matters:**
Each session should remember its own model and provider. This makes the app feel coherent and avoids state leakage.

**Implement:**
- store selected model per session
- store selected provider per session
- load them when opening a session
- reflect them clearly in the UI

**Outcome:**
Stable, predictable session behavior.

---

### 3. Explicit command approval and streaming execution

**Why it matters:**
Commands are dangerous by nature. They should be previewed, approved, and traceable.

**Implement:**
- command preview step
- approval state
- streaming stdout/stderr
- command history per session
- cancel/fail/completed states

**Outcome:**
Safe and observable execution.

---

### 4. File pull/push workflow

**Why it matters:**
File transfer is one of the core use cases and should be first-class, not bolted on.

**Implement:**
- file pull endpoint
- file push endpoint
- file metadata view
- chunked text file reading
- binary vs text handling

**Outcome:**
A real control-plane app, not just chat plus shell.

---

### 5. Secure pairing and trust flow

**Why it matters:**
The app needs strong trust boundaries before it becomes genuinely useful across devices.

**Implement:**
- pairing request/confirm flow
- trust token storage
- trust rotation and revocation
- explicit auth state in UI

**Outcome:**
Safer device-to-device and client-to-host communication.

---

## Codebase Advice to Improve Efficiency

### 1. Reduce global state

Avoid relying on a single `AppContext`-style singleton for everything.

**Better:**
- session-scoped state holders
- feature-scoped state models
- explicit dependencies passed through layers

This makes the code easier to test and less fragile.

---

### 2. Separate contracts from orchestration

Keep DTOs, API contracts, and persistence models cleanly separated from UI flow logic.

**Better structure:**
- `shared` for contracts and storage primitives
- `backend` for host execution and APIs
- `composeApp` for UI only

This will reduce coupling and make changes faster.

---

### 3. Make session state the unit of composition

If a feature belongs to a session, make the session the source of truth.

Examples:
- selected model
- provider
- chat history
- command history
- file history
- trust state

This prevents scattered state and duplicated logic.

---

### 4. Use explicit lifecycle states

Every important workflow should have clear states.

Examples:
- loading
- empty
- pending
- approved
- running
- failed
- completed

This avoids ad hoc UI branching and makes debugging easier.

---

### 5. Keep execution paths boring and traceable

For commands, file ops, and pairing, prefer simple flows with stable IDs and auditability.

Good habits:
- stable identifiers
- timestamps
- clear status transitions
- log the action once, not in three different places

This saves time later when you debug issues.

---

## Collaboration Idea for Multiple OpenClaw Instances

### Goal

Allow multiple OpenClaw instances on different machines to cooperate on a shared task.

Examples:
- one machine gathers logs
- another machine runs a build
- another machine transfers files
- all instances share task state and results

---

## Proposed Model: Shared Task Graph

Each OpenClaw instance can act as both:
- a **worker** that performs actions
- a **coordinator** that assigns or observes tasks

### Core concept
Create a shared collaboration layer with these primitives:

- **instance**: a machine running OpenClaw
- **task**: a request to do something
- **artifact**: files, outputs, logs, results
- **message**: status update or coordination note
- **capability**: what an instance can do

---

## Collaboration Flow

### 1. Discovery
Instances announce themselves to a shared coordination channel.

Each instance publishes:
- instance id
- hostname
- capabilities
- online status
- trust level

### 2. Task assignment
A coordinator creates a task and assigns it to one or more instances.

Examples:
- fetch a file
- run tests
- inspect logs
- compare outputs
- perform a build step

### 3. Shared status updates
Workers post status back:
- accepted
- running
- blocked
- completed
- failed

### 4. Artifact exchange
Instances share results through a common backend or artifact store.

This can include:
- files
- logs
- command output
- diffs
- screenshots

---

## Suggested Technical Design

### Option A, simplest: central coordination service

Use one backend as the shared coordinator.

Pros:
- easiest to build
- good control and auditability
- simple task routing

Cons:
- single coordination point

---

### Option B, decentralized with trusted relay

Use a lightweight relay or pub/sub layer.

Instances subscribe to:
- task queue
- status updates
- artifact references

Pros:
- more flexible
- can support multiple machines naturally

Cons:
- more moving parts

---

## Suggested MVP for Collaboration

Start with these 4 pieces:

1. **Instance registry**
   - register online instances
   - show capabilities

2. **Task posting**
   - create task
   - assign to instance

3. **Status relay**
   - send progress updates
   - mark completion

4. **Shared artifact references**
   - upload outputs
   - reference them in the task thread

---

## Collaboration Use Cases

- distributed debugging
- remote log collection
- split file operations
- build/test delegation
- multi-machine incident response

---

## Recommended Order

1. session management
2. per-session model/provider persistence
3. command approval + streaming
4. file transfer workflows
5. secure pairing
6. collaboration primitives

---

## Final Recommendation

If you want the project to feel real quickly, do this next:

- build the session list and session switcher
- persist per-session model/provider
- add command approval
- then add file actions

After that, the multi-instance collaboration layer becomes much easier because every machine can operate on the same task/session concepts.
