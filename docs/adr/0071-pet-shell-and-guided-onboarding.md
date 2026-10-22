# ADR-0071: Pet Shell and Guided Onboarding

- **Status:** Accepted
- **Date:** 2026-10-22
- **Related:** [Discreet Mode](../product/DISCREET_MODE.md), [Interactive spirit](../INTERACTIVE_SPIRIT.md)

## Decision

Add Chur as the fourth public application. The pet has no progression, score,
needs, network service, sound, or absence penalties. Its position and activity
belong to the current screen session. Reuse the bundled spirit asset and native
Filament renderers. Keep the neutral launch surface and the existing public apps.

A tap on the moon changes sleep. A hold requests the existing eight-digit
transition code. Code recognition routes to normal authentication. The existing
trial and successful authentication remain required to enable discreet mode.
Code drafts are never saved and are cleared when the host leaves the foreground.

Use a shared guide with fixed step and target identifiers. A target draws its own
highlight; the guide panel has separate layout space and does not intercept the
screen. Confirm action steps only from successful operations. Informational
steps have an explicit acknowledgment. Every step can be skipped. Skipping a
recovery explanation does not acknowledge or commit the recovery phrase.

The renderer receives only artwork state. The guide receives step outcomes,
not passwords, recovery words, file names, query text, or vault identities.
Lock and session replacement revoke the guide. An app-owned picker can suspend
the guide while the existing host-activity bracket preserves its session.

Record only a versioned tutorial status, outside backup. Do not persist private
navigation. Before constructing storage, detect an existing installation from
prior files or settings. Uncertain reads and unsupported status versions suppress
automatic onboarding and preserve existing bytes. Existing users can start the
guide explicitly from authenticated Settings. Public help teaches only the
selected public application.

## Consequences

Both native hosts need the pet route, public launcher action, and icon mapping.
The first-launch selector is a one-use exception for a public preference only;
ordinary shell changes still require an unlocked vault. Existing stored shell
values and unknown-value fallback remain unchanged.

Native appearance, touch delivery, accessibility, package size, memory, and
background rendering need platform verification. Host tests do not establish
physical-device acceptance. The iOS alternate icon retains the installed name.
