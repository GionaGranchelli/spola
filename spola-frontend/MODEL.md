# MODEL.md

## Model concept
A model is attached to a session, not just the app globally.

## Model data
A model should include:
- id
- display name
- provider
- context window
- capabilities
- default parameters

## Expected capabilities
- chat
- streaming
- tool use, if supported
- terminal-friendly output, if relevant

## Behavior
- Each session may override the default model.
- The app should remember the selected model per session.
- The backend should expose the available models.

## Practical rule
If a model cannot support a feature, the UI should degrade gracefully instead of hiding the whole session.