# Task 05: Security, Models, and Terminal

Implement the secure pairing and terminal-specific logic for remote bash execution.

## Goal
Enable secure remote command execution from the phone to the desktop host.

## Sub-tasks
- [ ] Implement backend pairing logic:
    - Generate a secret pairing token and display a QR code in the desktop terminal.
- [ ] Implement mobile pairing logic:
    - QR code scanner (Android/iOS native integrations).
    - Secure storage for the pairing token (Keychain/EncryptedSharedPreferences).
- [ ] Build the **Terminal Interface**:
    - Multi-line input for bash commands.
    - Real-time output streaming from the backend.
    - Visual indicators for "Running" vs. "Completed" commands.
- [ ] Model Selection logic:
    - Allow the user to pick a model per session or globally.
    - Fetch the latest available models from the backend.

[<- Back to Roadmap](roadmap.md)
