//! Small shared helpers: emoji names, local dates, core versions.

use yptun_core::isotime::{format_local_date, format_local_date_time, parse_iso_to_epoch_ms};
use yptun_core::util::parse_emoji_and_name;

fn split(raw: &str) -> (String, String) {
    parse_emoji_and_name(raw, "")
}

#[test]
fn flags_and_sequences_stay_whole() {
    assert_eq!(("🇫🇮".into(), "Helsinki".into()), split("  🇫🇮 Helsinki "));
    assert_eq!(("🇩🇪🇳🇱".into(), "EU".into()), split("🇩🇪🇳🇱EU"));
    // ZWJ family, skin tone, variation selector.
    assert_eq!(("👨‍👩‍👧".into(), "Home".into()), split("👨‍👩‍👧 Home"));
    assert_eq!(("👍🏽".into(), "ok".into()), split("👍🏽 ok"));
    assert_eq!(("❤️".into(), "Love".into()), split("❤️ Love"));
    assert_eq!(("⚡".into(), "Fast".into()), split("⚡Fast"));
    assert_eq!(("🚀".into(), "".into()), split("🚀"));
}

#[test]
fn names_without_a_leading_emoji_get_the_default() {
    assert_eq!(
        ("🌐".into(), "".into()),
        parse_emoji_and_name("Germany 🇩🇪", "🌐")
    );
    // A keycap starts with a plain digit: no emoji in front.
    assert_eq!(("".into(), "".into()), split("1️⃣ First"));
    assert_eq!(("x".into(), "".into()), parse_emoji_and_name("   ", "x"));
    assert_eq!(("".into(), "".into()), split(""));
}

#[test]
fn local_dates_shift_by_the_zone_offset() {
    let expiry = parse_iso_to_epoch_ms(Some("2026-01-31T22:30:00Z")).unwrap();

    assert_eq!("31.01.2026 22:30:00", format_local_date_time(expiry, 0));
    assert_eq!(
        "01.02.2026 01:30:00",
        format_local_date_time(expiry, 3 * 3600)
    );
    assert_eq!("01.02.2026", format_local_date(expiry, 3 * 3600));
    assert_eq!("31.01.2026", format_local_date(expiry, -5 * 3600));
    assert_eq!(
        "31.01.2026 17:30:00",
        format_local_date_time(expiry, -5 * 3600)
    );
}

#[test]
fn core_versions_are_published() {
    assert_eq!("0.0.4", yptun_core::core_versions::SNOLC);
    assert!(yptun_core::core_versions::OPENFLUX.starts_with("0.13.0"));
    assert!(yptun_core::core_versions::CSQTT.starts_with("2.5.0"));
    assert_eq!(
        yptun_core::core_versions::TRUSTTUNNEL_ANDROID,
        yptun_core::core_versions::TRUSTTUNNEL_DESKTOP
    );
}
