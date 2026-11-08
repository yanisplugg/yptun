//! Splits a leading emoji (if any) from the rest of a name (port of `EmojiUtils.kt`,
//! `parseEmojiAndName`).
//!
//! Handles multi-codepoint emoji correctly, especially country flags, which are a pair of
//! regional-indicator symbols (U+1F1EB U+1F1EE is the Finnish flag). Also consumes ZWJ sequences,
//! variation selectors, skin-tone modifiers and keycaps so the whole glyph stays together.
//!
//! Kotlin walked UTF-16 with its own surrogate-pair decoding; a Rust `str` is already a sequence of
//! code points, so the walk is the same without it.

/// Returns `(emoji, name)`. Exactly like the Kotlin original: when the trimmed name is empty or
/// does not start with an emoji, the result is `(default_emoji, "")`.
pub fn parse_emoji_and_name(raw_name: &str, default_emoji: &str) -> (String, String) {
    let trimmed = raw_name.trim();
    let first = match trimmed.chars().next() {
        Some(c) => c as u32,
        None => return (default_emoji.to_string(), String::new()),
    };
    if !is_emoji_code_point(first) {
        return (default_emoji.to_string(), String::new());
    }

    let end = trimmed
        .char_indices()
        .find(|(_, c)| {
            let cp = *c as u32;
            !is_emoji_code_point(cp) && !is_emoji_joiner(cp)
        })
        .map(|(i, _)| i)
        .unwrap_or(trimmed.len());

    if end == 0 {
        return (default_emoji.to_string(), String::new());
    }
    let emoji = &trimmed[..end];
    let name = trimmed[end..].trim();
    (emoji.to_string(), name.to_string())
}

fn is_emoji_code_point(cp: u32) -> bool {
    (0x1F1E6..=0x1F1FF).contains(&cp) // regional indicators (flags)
        || (0x1F300..=0x1FAFF).contains(&cp) // main emoji blocks
        || (0x2600..=0x27BF).contains(&cp) // misc symbols + dingbats
        || (0x2B00..=0x2BFF).contains(&cp) // stars, arrows
        || (0x1F000..=0x1F0FF).contains(&cp) // mahjong/dominoes/cards
        || cp == 0x2122
        || cp == 0x2139
        || (0x2190..=0x21FF).contains(&cp) // arrows
        || (0x2300..=0x23FF).contains(&cp) // technical (watch, alarm clock etc.)
}

fn is_emoji_joiner(cp: u32) -> bool {
    cp == 0x200D // zero-width joiner
        || cp == 0xFE0F
        || cp == 0xFE0E // variation selectors
        || cp == 0x20E3 // combining keycap
        || (0x1F3FB..=0x1F3FF).contains(&cp) // skin-tone modifiers
}
