//! Small shared helpers: emoji names, local dates, core versions, Kotlin digit parsing.

use yptun_core::isotime::{format_local_date, format_local_date_time, parse_iso_to_epoch_ms};
use yptun_core::util::{
    digit_value, filter_digits, is_digit, parse_emoji_and_name, to_int_or_null, utf16_len,
};

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

/// (input, `toIntOrNull()`, `filter(Char::isDigit).take(5).toIntOrNull() ?: 0`) as the Kotlin
/// app computed them.
const KOTLIN_INTS: &[(&str, Option<i32>, i32)] = &[
    ("", None, 0),
    ("0", Some(0), 0),
    ("443", Some(443), 443),
    ("+443", Some(443), 443),
    ("-1", Some(-1), 1),
    ("-", None, 0),
    ("+", None, 0),
    ("+-5", None, 5),
    ("2147483647", Some(2147483647), 21474),
    ("2147483648", None, 21474),
    ("-2147483648", Some(-2147483648), 21474),
    ("-2147483649", None, 21474),
    ("۴۴۳", Some(443), 443),
    ("٤٤٣", Some(443), 443),
    ("１２３", Some(123), 123),
    ("12a", None, 12),
    (" 12", None, 12),
    ("12 ", None, 12),
    ("00012", Some(12), 12),
    ("99999999999", None, 99999),
    ("4 4 3", None, 443),
    ("port: 8080", None, 8080),
    ("+０", Some(0), 0),
    ("−5", None, 5),
    ("1e3", None, 13),
    ("١٢٣٤٥٦٧", Some(1234567), 12345),
    ("abc", None, 0),
    ("443/tcp", None, 443),
    ("६५५३५", Some(65535), 65535),
    ("1１612aa27", None, 11612),
    ("०3", Some(3), 3),
];

#[test]
fn digits_parse_like_kotlin() {
    for &(input, int, port) in KOTLIN_INTS {
        assert_eq!(to_int_or_null(input), int, "toIntOrNull({input:?})");
        let digits: String = filter_digits(input).chars().take(5).collect();
        assert_eq!(
            to_int_or_null(&digits).unwrap_or(0),
            port,
            "port({input:?})"
        );
    }
}

#[test]
fn unicode_decimal_digits_are_digits() {
    // 370 BMP decimal digits (Nd), as Char.isDigit() saw them.
    let all: Vec<char> = (0..0x10000u32)
        .filter_map(char::from_u32)
        .filter(|&c| is_digit(c))
        .collect();
    assert_eq!(all.len(), 370);
    assert_eq!(digit_value('7'), Some(7));
    assert_eq!(digit_value('٣'), Some(3));
    assert_eq!(digit_value('９'), Some(9));
    assert_eq!(digit_value('a'), None);
    // Superscripts and fractions are not decimal digits; supplementary ones are outside a char.
    assert!(!is_digit('²') && !is_digit('½') && !is_digit('𝟎'));
    assert_eq!(filter_digits("a1²٣𝟎"), "1٣");
}

#[test]
fn lengths_count_utf16_units() {
    assert_eq!(utf16_len("abc"), 3);
    assert_eq!(utf16_len("ж"), 1);
    assert_eq!(utf16_len("🇷🇺"), 4);
    assert_eq!(utf16_len(""), 0);
}
