# YPtun — Master Grant Applications Tracker & Historical Dossier

> **Purpose:** Persistent single source of truth for all grant applications submitted to international internet freedom foundations. Preserves exact verbatim texts, submission dates, financial allocations, team representations, and compliance rules to ensure 100% consistency across subsequent review stages (Full Proposal, interviews, contracting, and milestone audits).

---

## 📑 Application Index

| # | Foundation / Program | Status | Requested Amount | Date Submitted | Submission ID / Portal |
|---|---|---|---|---|---|
| 1 | **Open Technology Fund (OTF)** — *Internet Freedom Fund (IFF-2026-11)* | 🟢 **Submitted (Under Review)** | **$60,000.00 USD** | 2026-11-23 | ID: **24481** (`apply.opentech.fund`) |
| 2 | **Human Rights Foundation (HRF)** — *Tech & Freedom Fund* | 🟢 **Submitted (Email)** | **$40,000.00 USD** | 2026-11-23 | `dev.fund@hrf.org` |
| 3 | **Sovereign Tech Fund (STF)** — *General Fund (Season A)* | 🟢 **Submitted (Under Review)** | **€65,000.00 EUR** | 2026-11-23 | Identifier: **`yOzEPpNM`** (`apply.sovereign.tech`) |
| 4 | **eQualitie** — *Breakout Program / SplinterCon* | 🟡 *Ready to Send* | **$35,000.00 USD** | 2026-11-23 | `info@equalitie.org` |
| 5 | **NLnet Foundation** — *NGI Zero Commons Fund* | ⚪ *On Hold (AI policy)* | **€50,000.00 EUR** | — | `nlnet.nl/propose/` |
| 6 | **Civil Rights Defenders (CRD)** | ⚪ *Queue* | **€20,000 – €40,000 EUR**| — | Direct inquiry |

---

## 1. Open Technology Fund (OTF) — Concept Note Submission Dossier

- **Program:** Internet Freedom Fund (IFF-2026-11)
- **Submission ID:** `24481`
- **Submitted On:** 2026-11-23 19:52 MSK
- **Submitted By / Account:** Artem Proskurov (`yptunn@gmail.com`)
- **Requested Funding:** `$60,000.00`
- **Estimated Duration:** `6 months to 1 year`
- **Attached Document:** `YPtun_Project_Overview_and_Verification.pdf` (Uploaded)

### 📋 Verbatim Submitted Form Fields

#### Field: Legal Name
```text
Artem Proskurov
```

#### Field: E-mail
```text
yptunn@gmail.com
```

#### Field: Describe your project in 1-3 sentences.
```text
YPtun defeats DPI censorship via multi-engine failover on mobile & PC to restore internet access.
```

#### Field: Briefly describe your or your team members’ relevant experience including previous projects, publications, or funding awards.
```text
Our team brings together four systems engineers and network security researchers:
1. Artem Proskurov — Lead iOS and Systems Engineer. Specializes in Apple’s network subsystem (NetworkExtension, PacketTunnelProvider), cross-platform runtime integration (Rust FFI, Cgo/gomobile), and low-level buffer tuning to operate strictly within the aggressive 15 MB iOS Jetsam memory ceiling. Engineered non-standard protocol runtimes for iOS. Contributed both directly and through the project profile yanisplugg (his personal GitHub account is currently awaiting manual review following a false-positive anti-abuse flag on an unsigned test binary).
2. Yan Desiatskii (GitHub: https://github.com/yanisplugg) — Founder and Lead Architect of YPtun. Designed the unified multi-engine architecture, manages community relations and documentation. Author of a comprehensive deep-dive research paper on DPI circumvention published on Habr: https://habr.com/ru/articles/1095778/
3. Stanislav Chernetsov (GitHub: https://github.com/Breead1337) — Core & Multiplatform Engineer. Leads desktop development across Kotlin Multiplatform / Compose, Wintun driver integration on Windows, Linux TUN interfaces, Android background services, and long-session memory leak mitigation.
4. Daniil Antonov — QA Infrastructure Lead & Financial Administrator. Manages testing workflows across physical device fleets, administers the Apple Developer account, and oversees compliance/finances (maintains a verified, compliant bank account in Kazakhstan capable of receiving direct SWIFT disbursements without sanction obstacles).

The team has not previously received grant funding; the project has been entirely self-funded and community-supported. Today, YPtun has reached version 4.0.0, with over 22,100 GitHub release downloads and over 2,000 active beta testers on Apple TestFlight.
```

#### Field: What problem will your project address? How does this project strengthen Internet freedom for human rights defenders, journalists, and/or activists operating in authoritarian and repressive environments?
```text
In heavily censored regions (Russia, Iran, Turkmenistan), state censors have evolved from simple IP/domain blocking to dynamic Deep Packet Inspection (TSPU/DPI), protocol throttling, and strict whitelisting regimes. Conventional VPN protocols (OpenVPN, WireGuard, legacy Shadowsocks) are routinely blocked nationwide within minutes. Furthermore, most modern circumvention tools (v2rayNG, Streisand, Happ) rely exclusively on standard proxy protocols and fail when non-standard ports are cut or protocols are blocked en masse.

YPtun solves this as an ultra-resilient, last-mile circumvention suite:
1. Uninterrupted Access During Shutdowns: If standard VLESS/Reality traffic is throttled or blocked, users can switch with a single tap to mieru, video-call stream masquerading (olcRTC), trusted WebRTC TURN relays (VK-TURN), obfuscated AmneziaWG, or ARQ-based DNS tunneling (MasterDNS).
2. Complete Decentralization & Privacy: YPtun operates without centralized VPN servers and collects zero telemetry. Users retain full ownership over their endpoints or can access an integrated, community-verified directory of free public servers without relying on a single vulnerable provider.
3. Power Tools for Journalists & Activists: Integrated client-side WARP and one-click cascading (chaining) allow users to obfuscate origin server IPs and link local white-list relays to external egress proxies without manual server-side routing configuration.
4. Seamless Split Tunneling: Advanced routing rules bypass FakeDNS for local domains, allowing activists and journalists to maintain an uncensored global uplink while preserving access to essential local services, media, and banking.
```

#### Field: If this project is funded, what form will it take?
```text
Technology Development
```

#### Field: Give a brief overview of the activities in this project.
```text
The proposed grant activities focus on dismantling technical distribution barriers and hardening client stability:

1. Desktop Client Hardening (Windows & Linux):
   - Optimize the Wintun virtual adapter integration to eliminate crashes, memory leaks, and UI hangs during high throughput and unexpected connection drops.
   - Implement fail-safe restoration of system IP routes and proxy settings upon unexpected application termination.

2. Code Signing & Trusted Distribution (Windows):
   - Procure and integrate a trusted EV/OV Code Signing Certificate for Windows binaries. This eliminates false-positive Windows Defender detections and SmartScreen warnings ("Unknown Publisher / Potentially Dangerous Software"), removing a major friction point for activists and general users.

3. iOS Engine Hardening Under Strict Memory Constraints (Jetsam 15 MB):
   - Low-level optimization of Go/Rust ring buffers and allocation pools within the NetworkExtension PacketTunnelProvider to prevent OS memory kills during cellular/Wi-Fi interface handovers.
   - Finalize App Store compliance hardening to expand distribution beyond TestFlight seat limitations.

4. Enhanced Anti-DPI Routing & Cascading Automation:
   - Implement real-time connection degradation detection with automatic, transparent failover across available fallback transports.
   - Expand the decentralized public server directory and introduce automated end-to-end route integrity checks.
```

#### Field: What stage of development is the project in? Provide any existing documentation for the project
```text
The project is at a mature, production-grade stage (Production / Public Beta) with an established and continuous release cycle:

- Current Version: YPtun 4.0.0 (Build 540 in TestFlight).
- Platforms: Android (full release), Windows (x64 and ARM64), Linux (.deb), iOS (public beta on Apple TestFlight).
- Proven Traction:
  • 22,100+ verified downloads across 37 GitHub releases.
  • 2,000+ active beta testers on Apple TestFlight (testing capacity near ceiling).
  • 270+ GitHub stars.

Official Links & Documentation:
1. Official Website: https://yptun.com/
2. Ecosystem Hub & Mirrors: https://dalink.to/yanisplugg
3. Open-Source Repository (GPL-3.0): https://github.com/yanisplugg/yptun
4. iOS Beta Program: https://testflight.apple.com/join/XqRGaz8p
5. Community Telegram: https://t.me/YPtun
6. In-Depth Technical Architecture & DPI Research: https://habr.com/ru/articles/1095778/
7. Comprehensive technical documentation, node deployment guides, and architectural blueprints are available directly in the repository (README.md, NODELESS_INFRASTRUCTURE.md).
```

#### Field: Are there similar projects that exist already? How is your project different or complementary to those projects?
```text
Existing censorship circumvention tools fall into three primary categories, compared to which YPtun occupies a distinct position:

1. Mainstream Proxy Clients (v2rayNG, Streisand, v2box, FoXray):
   - Limitations: Reliant exclusively on mainstream engines (Xray/sing-box). When censors enforce protocol-wide DPI bans or port whitelisting, these apps become inoperative.
   - YPtun Advantage: Bridges standard proxies with specialized deep-stealth transports (AmneziaWG, olcRTC video-call masquerading, VK-TURN WebRTC relays, MasterDNS). When one protocol class is suppressed, users seamlessly switch to an alternative within the same client.

2. Specialized Circumvention Suites (Amnezia VPN, Psiphon, Tor):
   - Limitations: Either require deploying and managing personal VPS infrastructure (Amnezia) or suffer from high latency and centralized entry nodes that are easily carpet-blocked (Tor/Psiphon).
   - YPtun Advantage: Maximum flexibility without server lock-in. Full support for universal subscription imports (Happ, raw Xray/sing-box JSON), zero-server client-side WARP, and one-click cascading from local bypass relays to external proxies.

3. Proprietary Commercial Clients (Happ, commercial VPN apps):
   - Limitations: Locked to paid server subscriptions, proprietary closed-source codebases, and intrusive telemetry.
   - YPtun Advantage: 100% open-source (GPL-3.0), zero server hosting costs via a pure Bring-Your-Own-Node architecture, and an integrated directory of verified public servers for journalists and activists who cannot afford foreign VPS infrastructure.
```

#### Field: How long do you estimate this project will take?
```text
6 months to 1 year
```

#### Field: Who would benefit from this project?
```text
The project directly serves three key target beneficiary groups operating in highly restrictive digital environments (primarily Russia, Belarus, Iran, and Central Asia):

1. Independent Journalists, Investigative Reporters, and Media Newsrooms:
Under aggressive state DPI filtering (such as Russia’s TSPU system), journalists frequently lose contact with sources and newsrooms when standard VPN protocols are throttled or banned nationwide. YPtun serves as a vital last-mile lifeline: its stealth transports (olcRTC video-call stream masquerading, VK-TURN WebRTC relays, and MasterDNS) ensure secure uplinks even during severe network throttling and white-listing regimes.

2. Human Rights Defenders (HRDs), Anti-Corruption Activists, and Civil Society NGOs:
For vulnerable activists, absolute privacy and the absence of a single point of failure are non-negotiable. YPtun’s decentralized Bring-Your-Own-Node (BYON) architecture means no user logs are collected, no accounts are required, and there is no centralized VPN infrastructure vulnerable to seizure or state-mandated takedowns. NGO IT coordinators can provision secure, customized multi-hop cascade profiles to at-risk staff via simple one-click links.

3. General Populations & At-Risk Civilians Lacking Foreign Payment Access:
Due to financial sanctions and payment restrictions, millions of at-risk citizens cannot purchase foreign VPS hosting or international VPN subscriptions. YPtun levels the playing field: with its integrated, verified open-source directory of public circumvention nodes and client-side WARP routing, non-technical users regain secure, free access to blocked independent media, YouTube, and Telegram with zero barrier to entry.

Community Engagement & Proven Reach:
Our team maintains continuous, direct engagement with our user base via our Telegram community (t.me/YPtun), collaborating with digital security trainers and front-line activists. YPtun’s real-world impact is already demonstrated by over 22,100 release downloads on GitHub and an active beta testing fleet of over 2,000 users on Apple TestFlight.
```

#### Field: Where are your intended users, or audiences located?
```text
Central Africa
Western Asia
Eastern Europe
```

#### Field: Did you use AI-assisted tools in preparing this application?
```text
Yes, an AI assistant was used strictly for language translation, grammatical refinement, and formatting the proposal into professional English, as our engineering team consists of non-native English speakers.

All technical data, platform metrics (version 4.0.0, 22,100+ GitHub downloads, 2,000+ TestFlight testers), architectural specifications (Wintun integration, iOS Jetsam 15 MB memory clamping, VK-TURN, MasterDNS, olcRTC), and milestone breakdowns originate entirely from our genuine open-source codebase, project documentation, and core developers.

Main prompt/instruction provided to the AI:
"Assist in translating and structuring our technical project documentation, real GitHub repository metrics, and planned development milestones into professional, concise English suitable for an Open Technology Fund (OTF) Concept Note application."
```

#### Confirmations & Checkboxes
- **At least 18 years of age:** Yes
- **Dismissed if not fit with OTF mission:** Yes
- **Terms & Privacy policy:** Yes
- **Openly licensed IP (GPL-3.0):** Yes
- **Agree to undergo code security audit facilitated by OTF:** Yes

---

## 2. Sovereign Tech Fund (STF, Germany) — Submission Dossier

- **Program:** Sovereign Tech Agency (Season A) — General Open Digital Base Technologies Fund
- **Application Identifier:** `yOzEPpNM`
- **Submitted On:** 2026-11-23 22:01 MSK
- **Applicant Name / Handle:** Yan Desiatskii (`yanisplugg`)
- **Applicant Profile:** `https://github.com/yanisplugg`
- **Account / Contact Email:** Artem Proskurov (`sender@app.goodgrants.com` confirmation sent to `yptunn@gmail.com`)
- **Requested Funding:** **€65,000.00 EUR**
- **Estimated Hours:** `1,850 hours`
- **Estimated Duration:** `8 months`
- **Country of Residence of Contract Signer:** `Kazakhstan` (Daniil Antonov, Freedom Bank)

### 📋 Verbatim Submitted Form Fields

#### Field: Project Title
```text
YPtun
```

#### Field: Describe your project in a sentence.
```text
YPtun is a fully open-source (GPL-3.0) multi-engine networking suite and core infrastructure that unifies mainstream and stealth proxy transports into a cross-platform stack (Android, Windows, Linux, iOS) to maintain communications under severe DPI filtering and network shutdowns.
```

#### Field: Describe your project more in-depth. Why is it critical?
```text
In authoritarian jurisdictions with pervasive network interference (such as Russia, Belarus, and Iran), state censorship agencies operate dynamic Deep Packet Inspection (DPI) and protocol whitelisting. Conventional single-protocol VPN architectures (OpenVPN, WireGuard, standard VLESS) suffer from systemic vulnerability: censors can suppress entire protocol signatures nationwide within minutes, leaving millions disconnected.

YPtun solves this fundamental architectural vulnerability by providing an open-source, decentralized multi-engine networking framework:
1. Unified Core Runtime: Consolidates disparate circumvention implementations—sing-box, Xray-core, AmneziaWG (obfuscated WireGuard), olcRTC (video-call stream masquerading), VK-TURN (enterprise WebRTC media tunneling), and MasterDNS (ARQ-based DNS encapsulation)—into a single high-performance native library. If one protocol layer is blocked, the engine switches to an alternative stealth transport.
2. Cross-Platform Protocol Layer: Standardizes low-level virtual network drivers (Wintun for Windows, TUN/TAP for Linux, and NetworkExtension PacketTunnelProvider for iOS) under a unified Kotlin Multiplatform business logic.
3. Bring-Your-Own-Node (BYON) Security: Operates with zero centralized server dependency, eliminating single points of failure, state surveillance seizure risks, and recurring hosting costs.
4. Open Public Ecosystem: Features an automated open-source scraper and validator of verified community proxy nodes, ensuring frontline civil society members without international banking access retain reliable internet connectivity.

With 22,100+ downloads and 2,000 active iOS TestFlight testers, YPtun serves as a critical open digital base technology for frontline human rights defenders and independent journalists.
```

#### Field: Link to project repository
`https://github.com/yanisplugg/yptun`

#### Field: Link to project website
`https://yptun.com/`

#### Field: Dependencies
```text
YPtun relies on several foundational open-source digital base technologies and libraries:
1. Low-Level Networking Cores & Drivers:
- sing-box / libbox (Go): Powers low-level packet routing, DNS resolution strategies, and next-generation transports (Hysteria 2).
- Xray-core (Go): Implements TLS camouflage (Reality), XHTTP, and flow multiplexing.
- Wintun (C): High-performance native TUN driver for Microsoft Windows network stack integration.
- Hev-socks5-tunnel / Tun2Socks: Cross-platform user-space TCP/UDP packet-to-socket translator.
- AmneziaWG-go: Low-level WireGuard protocol implementation with packet header and payload randomization.
- gomobile & Rust FFI: Compiles independent Go and Rust modules into static native libraries, avoiding IPC overhead and multi-process memory exhaustion.
2. Application Logic & Utilities:
- Kotlin Multiplatform & Jetpack Compose: Provides shared cross-platform state architecture, asynchronous event pipelines, and UI logic.
- zxing-cpp: Fast native C++ barcode/QR parser for configuration importing.
- Apple NetworkExtension (PacketTunnelProvider): iOS operating system network tunnel extension.
```

#### Field: Projects depending on technology
```text
1. Independent Media & Civil Society Organizations:
Independent newsrooms, human rights watchdogs, and investigative media groups reporting on Eastern Europe rely on YPtun to distribute pre-configured multi-hop proxies and private node profiles to at-risk field contributors via automated deep-links.
2. Downstream Protocol Implementations:
Specialized open-source circumvention protocols—specifically olcRTC (WebRTC video-call masquerading) and MasterDNS (DNS tunneling)—rely on YPtun as their primary reference client and consumer-facing integration, using our testing fleet for real-world DPI survivability data.
3. Decentralized Community Node Networks:
Independent volunteer operators and open-source subscription networks utilize YPtun's client-side parser to distribute Happ-compatible JSON manifests, WARP cascades, and XHTTP routing rules to thousands of users.
4. Open Node Scraper Ecosystem:
Our built-in open-source public server validator is utilized by community maintainers to continuously test, filter, and publish working circumvention endpoints for populations facing total payment isolation.
```

#### Field: Target Groups & Benefits
```text
YPtun serves three primary target user groups in heavily restricted digital environments:
1. Independent Journalists and Media Outlets (Direct Benefit):
During localized protests or political crackdowns, state telecommunications agencies throttle standard VPN traffic. YPtun provides immediate failover to stealth transports (olcRTC, VK-TURN, MasterDNS), ensuring continuous transmission of critical dispatches to international newsrooms.
2. Human Rights Defenders & Civil Society Activists (Direct Benefit):
Activists face targeted surveillance and legal prosecution. YPtun’s decentralized, zero-log BYON architecture ensures that no user identities, connection metadata, or payment trails exist on centralized servers. Activists can safely communicate and access blocked documentation.
3. General Populations Under Information Blockades (Direct & Indirect Benefit):
Millions of citizens in restricted regimes are isolated from independent global media, Wikipedia, and social platforms, while international payment blocks prevent them from purchasing foreign commercial VPNs. YPtun's integrated public server directory and client-side WARP routing provide robust, free access with zero financial barrier.

Benefit of Proposed STF Activities:
Hardening the Wintun driver, securing a trusted Windows Code Signing certificate, and optimizing iOS memory buffers will eliminate installation friction and prevent application crashes, allowing non-technical users to run circumvention software safely.
```

#### Field: Specific Use Scenario
```text
Scenario: Evading a Coordinated Protocol Blackout During Regional Unrest
Target User: An independent regional journalist documenting localized internet censorship and civic demonstrations.
1. The Threat: State telecommunications authorities deploy TSPU DPI firewalls to block all standard proxy ports and protocol handshakes (OpenVPN, WireGuard, and standard VLESS). Commercial VPN applications fail to connect or suffer from 95% packet drops.
2. The Solution: The journalist launches YPtun. The integrated latency probe flags connection failure on standard outbounds. With a single tap, the user activates the VK-TURN engine.
3. Protocol Masquerading: YPtun encapsulates the encrypted tunnel traffic inside WebRTC DTLS media streams and routes them through whitelisted domestic enterprise voice-call servers (VK relays). The censor's DPI classifies the packets as an ordinary, unblocked domestic audio/video call.
4. Split Routing & Fail-Safe: Simultaneously, YPtun applies intelligent split tunneling: local sources and domestic banking continue directly via local IP to avoid suspicious foreign login flags, while uncensored news and international channels pass through the encrypted tunnel. If carrier-level filtering escalates to block all UDP ports, YPtun seamlessly falls back to MasterDNS, tunneling dispatches through standard recursive DNS queries.
Outcome: The journalist maintains secure, unbroken communication with editors and publishes vital reporting without leaking metadata or exposing endpoint IP addresses.
```

#### Field: Past Funding & Sustainability
```text
All development and maintenance of YPtun to date have been carried out through the completely self-funded, volunteer efforts of our four core systems engineers, working on personal savings and modest grassroots community donations:
1. Self-Funded Engineering: Our core team has invested its own personal financial resources and uncompensated labor to architect the multi-engine Go/Rust runtime, port the Kotlin Multiplatform client across four platforms (Android, Windows, Linux, iOS), and maintain 37 continuous public releases.
2. Community Infrastructure & Testing: All testing infrastructure, device fleet verification, and public server validation have been funded entirely out of pocket by the developers, supplemented by occasional micro-donations from our active testing community.
3. Zero Institutional Funding: The project has never received venture capital, corporate sponsorships, institutional funding, or government grants. Development has reached a critical bottleneck where sustaining multi-platform driver maintenance, continuous DPI testing, and procuring required security certificates (EV/OV code signing) can no longer be sustained solely on personal savings.
```

#### Field: Current Maintenance Challenges
```text
As YPtun's user base exceeds 22,100 downloads, our small team faces critical technical and infrastructure maintenance bottlenecks:
1. Code Signing & Distribution Barriers on Windows: Our Windows client lacks a trusted EV/OV Code Signing Certificate. Microsoft Windows Defender and SmartScreen flag our open-source installer as "Unknown Publisher / Potentially Dangerous Software." This false positive severely frightens non-technical journalists and activists, creating a massive barrier to adoption. Procuring trusted hardware/cloud HSM code signing exceeds our volunteer budget.
2. Virtual Adapter & Driver Stability (Wintun): Under heavy, sustained throughput, handling concurrent routing table updates and interface resets occasionally causes Wintun session deadlocks and memory retention issues in the JVM/Compose layer. We urgently need dedicated engineering time to refactor low-level socket lifecycles and build automated network state restoration upon unexpected process termination.
3. Apple iOS Jetsam 15 MB Memory Ceiling: Apple's NetworkExtension enforces a strict 15 MB memory ceiling for PacketTunnelProvider extensions. Running concurrent Go and Rust networking routines under high packet rates causes the OS to terminate the VPN process during cellular-to-Wi-Fi handovers. Re-architecting ring buffers and minimizing Cgo allocations requires labor-intensive optimization.
4. Continuous Automated Testing Fleet: Maintaining real-time compatibility across constantly shifting DPI signatures in hostile networks requires automated CI/CD matrix runners and test nodes, which we currently lack the resources to maintain.
```

#### Field: Alternatives & Comparison
```text
Existing circumvention solutions fall into three main categories:
1. Mainstream Proxy Clients (v2rayNG, Streisand, v2box):
- Limitations: Reliant exclusively on mainstream cores (Xray or sing-box) implementing standard VLESS, VMess, or Trojan. When state DPI firewalls enact protocol-wide bans or enforce port whitelisting, these clients become completely inoperative.
- YPtun Advantage: YPtun bridges mainstream protocols with specialized deep-stealth transports (olcRTC video masquerading, VK-TURN enterprise WebRTC relays, MasterDNS, and obfuscated AmneziaWG). If one protocol class is suppressed, the client immediately switches to an alternative transport within the same runtime.
2. Centralized Commercial VPNs (Happ, consumer VPN providers):
- Limitations: Depend on proprietary server farms that are easily IP-blocked by regulators. Financial sanctions prevent frontline users from purchasing subscriptions, and closed-source clients present severe privacy risks.
- YPtun Advantage: 100% open-source (GPL-3.0) and decentralized. Operates on a pure Bring-Your-Own-Node model with zero central server overhead, zero user logging, and an integrated directory of verified public servers for users without payment cards.
3. Standalone Privacy Tools (Tor, Amnezia VPN):
- Limitations: Tor suffers from severe speed limitations and easy bridge throttling; Amnezia requires complex personal VPS setup.
- YPtun Advantage: High-throughput performance, zero-configuration client-side WARP, one-click cascading, and accessible subscription importing.
```

#### Field: Scope of Work (Work Packages)
```text
With the support of the Sovereign Tech Fund, our primary objective is to harden the security, memory stability, and distribution resilience of the YPtun multi-engine networking stack across desktop and mobile platforms. The work is structured into four core technical work packages:

Work Package 1: Driver Stability & Kernel-Level Virtual Adapter Hardening (Windows Wintun & Linux TUN)
- Refactor the Wintun session lifecycle management to eliminate concurrency deadlocks, socket descriptor leaks, and memory retention during sustained 24/7 background operation.
- Implement an atomic fail-safe state machine for system IP routing and DNS configuration: ensure that in the event of an abrupt process crash, kernel sleep/wake cycle, or sudden driver restart, all system routing tables, interfaces, and DNS resolvers are cleanly and automatically rolled back to their native state, preventing network black-holing.
- Optimize packet buffer allocation within the JVM/Desktop Compose bridge to reduce CPU and garbage collection pressure under multi-gigabit throughput.

Work Package 2: Trusted Cryptographic Code Signing & Binary Integrity Pipeline
- Procure and deploy an official EV/OV Code Signing Certificate backed by cloud-based Hardware Security Module (HSM) signing.
- Integrate automated cryptographic signing into our public GitHub Actions CI/CD release workflow for both x64 and native ARM64 Windows binaries.
- Permanently resolve Microsoft SmartScreen and Windows Defender false-positive warnings ("Unknown Publisher / Potentially Dangerous Software"), eliminating the primary psychological and security barrier that currently prevents at-risk journalists and civil society activists from safely deploying circumvention tools.
- Implement automated reproducible builds and publish detached cryptographic signatures and SHA-256 checksum manifests for every release.

Work Package 3: Low-Level Memory Clamping & iOS Jetsam 15 MB Optimization
- Re-architect the unified Go/Rust static runtime library (Coreapi.xcframework / gomobile) to operate strictly within Apple's mandatory 15 MB memory ceiling for PacketTunnelProvider extensions.
- Implement zero-copy packet processing and custom ring buffers for high-rate UDP/QUIC traffic (Hysteria 2 and AmneziaWG).
- Optimize Cgo/Rust FFI memory allocation pools to eliminate heap fragmentation spikes during network interface transitions (cellular LTE/5G to Wi-Fi handover), completely preventing OS Jetsam SIGKILL termination.

Work Package 4: Automated DPI Degradation Detection & Dynamic Multi-Engine Failover
- Engineer lightweight, real-time connectivity probes within the sing-box/Xray routing layer to detect active DPI throttling, selective SNI dropping, and TCP RST packet injection.
- Implement seamless, autonomous failover: when standard proxy protocols (VLESS/Reality) are degraded or blocked by state firewalls, the engine automatically pivots the active tunnel to stealth fallback transports (VK-TURN WebRTC media tunneling, olcRTC masquerading, or MasterDNS ARQ encapsulation) without terminating active user connections.
- Harden the automated open-source public server validator to continuously verify route integrity and filter out poisoned or malicious public endpoints.
```

#### Field: Qualified Team
```text
The proposed work will be executed directly by the four core systems engineers and architects who created YPtun and possess deep, firsthand expertise in overcoming active DPI censorship:

1. Artem Proskurov (Lead iOS & Systems Engineer):
Specializes in Apple NetworkExtension (PacketTunnelProvider), low-level memory buffer optimization, Rust FFI runtimes, and Cgo integration. He directly designed the iOS packet-to-socket bridge and is uniquely qualified to execute the 15 MB Jetsam memory clamping work package.

2. Yan Desiatskii (Founder & Chief Architect):
Original creator of YPtun who designed the unified multi-engine process architecture combining sing-box, Xray, AmneziaWG, and WebRTC into a single Go-runtime process. Author of the authoritative DPI circumvention paper on Habr. He oversees core routing mechanics, upstream protocol integration, and community testing feedback.

3. Stanislav Chernetsov (Core & Multiplatform Engineer):
Leads desktop client development (Kotlin Multiplatform / Compose JVM), Wintun driver integration on Windows, and Linux TUN/TAP routing. He is responsible for driver lifecycle refactoring, route rollback implementation, and memory leak mitigation.

4. Daniil Antonov (QA & Financial Administrator):
Coordinates real-world device testing across a fleet of physical devices and test nodes under active censorship networks, administers the Apple Developer portal, and oversees financial/legal compliance through verified banking infrastructure in Kazakhstan.

Why this team is uniquely qualified:
Having developed YPtun from scratch through 37 production releases to over 22,100 active downloads, the team has proven technical mastery over the codebase and possesses intimate knowledge of frontline DPI firewall evasion techniques that external contractors cannot replicate.
```

---

## 3. Team Directory & Identity Anchors

| Name | Role in Application | Primary Responsibility | Technical Handles / Identifiers |
|---|---|---|---|
| **Artem Proskurov** | Lead iOS & Systems Engineer *(Applicant)* | Apple NetworkExtension, Rust FFI, Jetsam 15MB buffer tuning, Cgo runtimes | `yprosk21@gmail.com`, `yptunn@gmail.com`, GitHub: `Zamotashka` |
| **Yan Desiatskii** | Founder & Chief Architect | Unified multi-engine architecture, community relations, documentation, Habr research | `yanisplugg@gmail.com`, GitHub: `yanisplugg` |
| **Stanislav Chernetsov** | Core & Multiplatform Engineer | Windows Wintun driver, Linux TUN/TAP, Compose Desktop, Android background services | GitHub: `Breead1337` |
| **Daniil Antonov** | QA & Financial Lead | Testing fleet coordinator, Apple Developer Account Admin, designated financial payee | Bank payee in Kazakhstan (Freedom Bank) |

---

## 3. Financial & Legal Safety Guidelines (Strictly Enforced)

> [!CAUTION]
> **Zero Inflow to Russian Banks:** Under no circumstances should grant disbursements or funds from Kazakhstan be transferred directly to Russian bank accounts (Sber, Tinkoff/T-Bank, Alfa, VTB). Direct inward foreign transfers from grant foundations or non-resident accounts trigger currency control flags (115-ФЗ), tax audits, and severe legal risks (including "Foreign Agent" designation under Federal Law No. 255-FZ).

### Operational Protocol for Grant Funds:
1. **Primary Receiving Channel:** Daniil Antonov via verified bank account in Kazakhstan (Freedom Bank / BCC) using USD SWIFT wire transfers with the signed contract and milestone invoice as legal justification.
2. **Alternative Corporate Entity (Recommended for $100k+):** Remote US LLC (Wyoming) formed via Firstbase/Doola + Mercury/Relay Bank corporate account.
3. **Usage of Funds:** Exclusively offshore. Direct card payments for international relocation expenses, flight tickets, visa fees, apartment rent abroad, and legal/relocation services.
4. **Domestic Living Expenses (if necessary while in Russia):** Isolated, low-volume P2P cryptocurrency (USDT) conversions through domestic peer-to-peer exchanges without linking to the primary grant accounts.

---

## 4. Next Grants in Pipeline

### Target 1: NLnet Foundation (NGI Zero Commons Fund)
- **Award:** Up to **€50,000 EUR** (~$55,000 USD).
- **Portal:** `https://nlnet.nl/propose/`
- **Application Structure:** Single-stage 2-page lightweight proposal.
- **Key Theme:** Resilient open-source internet stack, protocol independence, privacy, zero central server dependency.
- **Supporting Attachment:** Re-use `YPtun_Project_Overview_and_Verification.pdf`.

### Target 2: Human Rights Foundation (HRF)
- **Award:** **$25,000 – $50,000 USD**.
- **Contact:** `dev.fund@hrf.org` / online pitch.
- **Key Theme:** Circumvention of state censorship in authoritarian regimes (Russia, Belarus, Iran), protecting investigative journalists and dissidents.
