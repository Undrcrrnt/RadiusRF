## What does this change?

<!-- What does this PR do, and why? -->

## Testing

<!--
Most features depend on real hardware — please confirm you tested on a physical device,
and which one. Emulator-only testing is fine for pure logic/UI changes but should be noted.
-->

- [ ] Tested on a physical device (model: )
- [ ] `./gradlew test` passes
- [ ] `./gradlew assembleDebug` succeeds

## Checklist

- [ ] New tool: registered in `navigation/RadiusRfScreen.kt`, DI wired via the relevant `core/di/*Module.kt`, documented in [`docs/TOOLS.md`](../docs/TOOLS.md)
- [ ] `domain/` code has no Android UI imports
- [ ] No new permissions requested without updating the README's permission table
