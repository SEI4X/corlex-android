# Consent and collected data

The host application owns its consent flow and store/privacy declarations. Use pending consent until that flow permits collection; optIn starts delivery and optOut stops/wipes pending SDK state. Default granted mode assumes the host already obtained any necessary consent; this SDK does not establish legal compliance.

Automatic metadata: random anonymous ID, event ID/time, platform/OS version, app version/build, locale and device model. Lifecycle and static screen names are supported; no advertising ID, address book or financial record is read. Explicit track/identify/user-property values remain the caller's responsibility. Prefer internal pseudonymous user IDs; never add phone/email or free-text transaction content by default.

Automatic messages are generic by default; captureErrorMessages is an explicit opt-in for potentially sensitive details.

SDK project keys are client-visible write-only keys. Privileged MCP, upload and provider keys remain on the developer/CI/server side. TLS validation is provided by the platform and must never be disabled. HTTP development overrides, where supported, must be explicit and conditioned on the host application's development mode, not the compiled library's build mode.
