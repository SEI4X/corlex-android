# Troubleshooting

- No events: check explicit consent, correct project/test key and owning-cell ingest/config origins. Open the app normally, describe app_open/screen_view, then verify delivery.
- Simulator networking: iOS Simulator uses host localhost; Android emulator uses 10.0.2.2/10.0.3.2. A phone needs a reachable development/HTTPS address. Preserve platform network policy.
- 401/403: the project key is invalid/revoked; regenerate through the authorized owner flow and rebuild configuration. Never paste keys into issue logs.
- Missing native plugin: rebuild the native app; hot reload and Expo Go do not install custom native modules.
- Unreadable native stack: verify release UUID/build/architecture and matching uploaded symbols, not just an SDK connection check.
- Config stays at default: check document validity, consent and release publication. Values may intentionally apply only on the next launch.
- Reporting a bug: include SDK/toolchain/OS versions, redacted diagnostics and minimal synthetic reproduction; use private vulnerability reporting for security findings.
