# Task 02: Shared Module Development

Define the common domain models and communication interfaces shared between backend and frontend.

## Goal
Establish a single source of truth for the data passed over the network and stored in the database.

## Sub-tasks
- [x] Define shared DTOs for `ChatSession`, `Message`, `ModelInfo`, and `BashCommandRequest`.
- [x] Set up SQLDelight in the `shared` module for a common database schema.
- [x] Implement a basic `Ktor` network client in `shared` for common API calls.
- [x] Add serialization support using `kotlinx.serialization`.

[<- Back to Roadmap](roadmap.md)
