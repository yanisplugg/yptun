//! UI texts shared by all platforms. The catalogs core/yptun-core/i18n/<id>.json are generated
//! from the Kotlin Strings.kt / BeStrings.kt / CsStrings.kt / SkStrings.kt (plain strings and
//! {name} templates). The 20 strings with logic (plurals, conditions, lists) are functions in
//! crate::i18n_dynamic.

use std::collections::BTreeMap;
use std::sync::LazyLock;

/// Supported UI languages. System follows the device locale (resolved by the platform).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum AppLanguage {
    System,
    English,
    Russian,
    Persian,
    Chinese,
    Slovak,
    Czech,
    Belarusian,
}

/// What System resolves to until the platform says otherwise (Kotlin systemLanguage).
pub const DEFAULT_SYSTEM_LANGUAGE: AppLanguage = AppLanguage::Russian;

impl AppLanguage {
    /// Kotlin entries order.
    pub const ALL: [AppLanguage; 8] = [
        AppLanguage::System,
        AppLanguage::English,
        AppLanguage::Russian,
        AppLanguage::Persian,
        AppLanguage::Chinese,
        AppLanguage::Slovak,
        AppLanguage::Czech,
        AppLanguage::Belarusian,
    ];

    /// The id stored in settings.
    pub fn id(self) -> &'static str {
        match self {
            AppLanguage::System => "system",
            AppLanguage::English => "en",
            AppLanguage::Russian => "ru",
            AppLanguage::Persian => "fa",
            AppLanguage::Chinese => "zh",
            AppLanguage::Slovak => "sk",
            AppLanguage::Czech => "cs",
            AppLanguage::Belarusian => "be",
        }
    }

    /// Unknown or missing id gives System (Kotlin fromId).
    pub fn from_id(id: Option<&str>) -> AppLanguage {
        AppLanguage::ALL.iter().copied().find(|l| Some(l.id()) == id).unwrap_or(AppLanguage::System)
    }

    /// The language actually shown: System becomes the device language.
    pub fn effective(self, system_language: AppLanguage) -> AppLanguage {
        if self == AppLanguage::System {
            system_language
        } else {
            self
        }
    }
}

type Catalog = BTreeMap<String, String>;

fn parse(json: &str) -> Catalog {
    serde_json::from_str(json).expect("bad i18n catalog")
}

static RU: LazyLock<Catalog> = LazyLock::new(|| parse(include_str!("../i18n/ru.json")));
static EN: LazyLock<Catalog> = LazyLock::new(|| parse(include_str!("../i18n/en.json")));
static FA: LazyLock<Catalog> = LazyLock::new(|| parse(include_str!("../i18n/fa.json")));
static ZH: LazyLock<Catalog> = LazyLock::new(|| parse(include_str!("../i18n/zh.json")));
static SK: LazyLock<Catalog> = LazyLock::new(|| parse(include_str!("../i18n/sk.json")));
static CS: LazyLock<Catalog> = LazyLock::new(|| parse(include_str!("../i18n/cs.json")));
static BE: LazyLock<Catalog> = LazyLock::new(|| parse(include_str!("../i18n/be.json")));

/// All texts of a language (System is Russian, as Kotlin stringsFor).
pub fn catalog(lang: AppLanguage) -> &'static BTreeMap<String, String> {
    match lang {
        AppLanguage::English => &*EN,
        AppLanguage::Persian => &*FA,
        AppLanguage::Chinese => &*ZH,
        AppLanguage::Slovak => &*SK,
        AppLanguage::Czech => &*CS,
        AppLanguage::Belarusian => &*BE,
        AppLanguage::Russian | AppLanguage::System => &*RU,
    }
}

/// Every key of the catalogs (all catalogs have the same keys).
pub fn keys() -> impl Iterator<Item = &'static str> {
    RU.keys().map(|k| k.as_str())
}

pub fn lookup(lang: AppLanguage, key: &str) -> Option<&'static str> {
    catalog(lang).get(key).map(|s| s.as_str())
}

/// The text for key; falls back to Russian, then to the key itself.
pub fn text<'a>(lang: AppLanguage, key: &'a str) -> &'a str {
    lookup(lang, key).or_else(|| lookup(AppLanguage::Russian, key)).unwrap_or(key)
}

/// text() with {name} placeholders replaced, e.g. format(lang, key, &[("count", "3")]).
pub fn format(lang: AppLanguage, key: &str, args: &[(&str, &str)]) -> String {
    let mut s = text(lang, key).to_string();
    for &(name, value) in args {
        s = s.replace(&["{", name, "}"].concat(), value);
    }
    s
}

/// Russian / Belarusian plural (Kotlin ruPlural): one / few / many.
pub fn ru_plural<'a>(n: i64, one: &'a str, few: &'a str, many: &'a str) -> &'a str {
    let mod100 = n % 100;
    let mod10 = n % 10;
    if (11..=14).contains(&mod100) {
        many
    } else if mod10 == 1 {
        one
    } else if (2..=4).contains(&mod10) {
        few
    } else {
        many
    }
}

/// "40 s", "8 min", "1 h 5 min" with the language's unit words (Kotlin etaText).
pub fn eta_text(sec: i64, s: &str, m: &str, h: &str) -> String {
    if sec < 90 {
        format!("{} {s}", sec.max(1))
    } else if sec < 90 * 60 {
        format!("{} {m}", (sec + 30) / 60)
    } else {
        format!("{} {h} {} {m}", sec / 3600, (sec % 3600) / 60)
    }
}
