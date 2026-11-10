# Native Apple UI brief

Scope: Native iOS and macOS operational shell. Mode: Operate. The first implemented surface is the connection home and its shared visual system; platform navigation remains native.

Audience/job: A person under unreliable or censored connectivity must understand current VPN state, choose a location, connect, and diagnose a failure without decoding networking jargon. Advanced controls remain reachable in the same hierarchy as the current app.

Constraints: Preserve YPtun identity, cat asset, feature hierarchy, Dynamic Type, VoiceOver, Reduce Motion/Transparency, dark and light modes. Use only native SwiftUI navigation and controls. Liquid Glass is selective progressive enhancement on iOS/macOS 26; iOS 17–18 and macOS 14–15 use system materials. No glass card grid.

## Direction contract

**THESIS:** YPtun is a calm signal passing through hostile noise. The home surface makes connection state unmistakable and keeps the existing location-first workflow, refusing the category-default wall of equal VPN dashboard cards and decorative telemetry.

**OWN-WORLD:** System backgrounds carry content; YPtun blue carries actions and live signal, with semantic green, amber, and red reserved for state. Navigation/tool surfaces may use one system glass/material layer, while lists and forms remain solid and legible. SF typography, SF Symbols, restrained radii, and the existing cat asset keep the product recognizable without imitating Compose.

**STORY:** The user first reads whether protection is active, which location owns the session, and the one action available now. They can change location in place, then move through the familiar add/import, subscriptions, routing, logs, session, and settings hierarchy. Errors name the failing layer and offer the next action.

**FIRST VIEWPORT:** iPhone opens with a native large-title toolbar, one compact status field occupying the visual center, the active location immediately below, and one full-width connect control within thumb reach. Recent locations continue as a native list rather than cards. macOS maps the same hierarchy into a sidebar, detail status surface, toolbar, and menu-bar state. The signature move is the connection control coherently materializing from translucent idle chrome into a stable, higher-contrast connected control while its status glyph traces one restrained signal pulse.

**FORM:** User-pinned Apple-native operational interface with selective Liquid Glass; grounded direction 7 under seed `b4675e95`. It inherits platform navigation and controls, using the visual world only through palette, density, typography, and the single signal-materialization move.

**FINISH:** unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

Unresolved: final screenshots and exact glass API behavior require Xcode 26 simulators and physical-device verification; no visual claim is approved from Windows-only placeholders.
