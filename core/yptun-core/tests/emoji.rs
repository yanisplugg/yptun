use yptun_core::emoji::parse_emoji_and_name;

fn p(raw: &str, default: &str) -> (String, String) {
    parse_emoji_and_name(raw, default)
}

fn pair(a: &str, b: &str) -> (String, String) {
    (a.to_string(), b.to_string())
}

#[test]
fn flag_is_split_whole() {
    assert_eq!(p("  \u{1F1EB}\u{1F1EE} Finland ", ""), pair("\u{1F1EB}\u{1F1EE}", "Finland"));
}

#[test]
fn empty_or_no_emoji_gives_default_and_empty_name() {
    assert_eq!(p("   ", "\u{1F310}"), pair("\u{1F310}", ""));
    assert_eq!(p("", ""), pair("", ""));
    assert_eq!(p("Finland", "x"), pair("x", ""));
    // A keycap starts with a plain digit, so it is not an emoji start (same as Kotlin).
    assert_eq!(p("1\u{FE0F}\u{20E3} one", "d"), pair("d", ""));
}

#[test]
fn joiners_and_modifiers_stay_with_the_glyph() {
    assert_eq!(
        p("\u{1F3F3}\u{FE0F}\u{200D}\u{1F308}Pride", ""),
        pair("\u{1F3F3}\u{FE0F}\u{200D}\u{1F308}", "Pride")
    );
    assert_eq!(p("\u{1F44D}\u{1F3FD} ok", ""), pair("\u{1F44D}\u{1F3FD}", "ok"));
    assert_eq!(p("\u{2B50}Star", ""), pair("\u{2B50}", "Star"));
}

#[test]
fn only_the_leading_emoji_is_taken() {
    assert_eq!(
        p("\u{1F1E9}\u{1F1EA}  Berlin \u{1F600}", ""),
        pair("\u{1F1E9}\u{1F1EA}", "Berlin \u{1F600}")
    );
    assert_eq!(p("\u{1F600}", ""), pair("\u{1F600}", ""));
}
