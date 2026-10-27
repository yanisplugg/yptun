# Security policy

olcRTC carries other people's traffic and hides the shape of it. A flaw here can
expose exactly what someone went out of their way to protect, so security
reports are treated as the most important work in the queue.

## Reporting a vulnerability

**Please do not open a public issue.**

Use GitHub's private vulnerability reporting:
<https://github.com/ghostlane-project/olcrtc/security/advisories/new>

It is private between you and the maintainers, takes attachments, and becomes a
published advisory crediting you once a fix ships. If you cannot use it, write
to <securityghostlane@proton.me>, the security contact for olcRTC and
Ghostlane.

Automated review is welcome on the same terms. What the engine defends, against
whom, and how we rate severity are in
[.oss-scanner/threat_model.md](.oss-scanner/threat_model.md).

## What to include

The version or commit, the transport and provider involved, what an attacker
must be able to do first, what they get, and the smallest reproduction you have.
A proof of concept is welcome and never required.

## What to expect

- An acknowledgement within three days.
- An assessment within two weeks: whether we agree, how serious we think it is,
  and what we intend to do.
- A fix in a release, and an advisory crediting you unless you prefer otherwise.

There is no bounty programme today.

## Scope

In scope: this engine, its transports and its handshake, the keys and metering
it manages, and anything it ships in a release.

Out of scope, though worth telling us about anyway: the meeting services the
engine speaks to (Jitsi deployments, Yandex Telemost, WB Stream, SaluteJazz, VK
Calls, MTS Link, MAX), the operators
of relays you connect through, and the applications that embed this engine other
than [Ghostlane](https://github.com/ghostlane-project/ghostlane).

## Disclosure

We ask for time to fix before publication, and we do not ask for silence. Ninety
days is the default; longer if we say why, sooner if the fix is out sooner. A
flaw already being exploited changes the timetable, not the courtesy.

## What this software does not promise

It disguises a tunnel as a media session. It does not make its user anonymous,
it cannot help against an adversary who controls the device, and the meeting
service can always see that a call took place and how much it carried. Those are
properties of the design and are documented; a report showing one of them is
weaker in practice than documented is very welcome.
