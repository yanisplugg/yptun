//! UI strings with logic (plurals, conditions, lists), ported one-to-one from the Kotlin
//! RuStrings / EnStrings / FaStrings / ZhStrings / BeStrings / CsStrings / SkStrings.
//! Plain strings live in the crate::i18n catalogs. System is shown as Russian, like stringsFor.

use crate::i18n::AppLanguage as L;
use crate::i18n::{eta_text, ru_plural};

/// Czech plural (Kotlin csPlural): 1 / 2..4 / other.
pub fn cs_plural<'a>(n: i64, one: &'a str, few: &'a str, many: &'a str) -> &'a str {
    match n {
        1 => one,
        2..=4 => few,
        _ => many,
    }
}

/// Slovak plural (Kotlin skPlural): 1 / 2..4 / other.
pub fn sk_plural<'a>(n: i64, one: &'a str, few: &'a str, many: &'a str) -> &'a str {
    match n {
        1 => one,
        2..=4 => few,
        _ => many,
    }
}

/// Order of the per-language tables below: ru, en, fa, zh, be, cs, sk.
fn ix(lang: L) -> usize {
    match lang {
        L::Russian | L::System => 0,
        L::English => 1,
        L::Persian => 2,
        L::Chinese => 3,
        L::Belarusian => 4,
        L::Czech => 5,
        L::Slovak => 6,
    }
}

pub fn domain_strategy_name(lang: L, v: &str) -> String {
    const T: [[&str; 4]; 7] = [
        ["Предпочитать IPv4", "Предпочитать IPv6", "Только IPv4", "Только IPv6"],
        ["Prefer IPv4", "Prefer IPv6", "IPv4 only", "IPv6 only"],
        ["ترجیح IPv4", "ترجیح IPv6", "فقط IPv4", "فقط IPv6"],
        ["优先 IPv4", "优先 IPv6", "仅 IPv4", "仅 IPv6"],
        ["Аддаваць перавагу IPv4", "Аддаваць перавагу IPv6", "Толькі IPv4", "Толькі IPv6"],
        ["Upřednostnit IPv4", "Upřednostnit IPv6", "Pouze IPv4", "Pouze IPv6"],
        ["Uprednostniť IPv4", "Uprednostniť IPv6", "Iba IPv4", "Iba IPv6"],
    ];
    let t = &T[ix(lang)];
    match v {
        "prefer_ipv4" => t[0].to_string(),
        "prefer_ipv6" => t[1].to_string(),
        "ipv4_only" => t[2].to_string(),
        "ipv6_only" => t[3].to_string(),
        _ => v.to_string(),
    }
}

pub fn sb_routing_advanced_desc(lang: L) -> &'static str {
    const T: [&str; 7] = [
        concat!("Дословный JSON sing-box. Выполняется до переключателей выше. ", "Пример правил: [{\"domain_suffix\":[\"openai.com\"],\"outbound\":\"direct\"}]"),
        concat!("Verbatim sing-box JSON. These run before the toggles above. ", "Rules example: [{\"domain_suffix\":[\"openai.com\"],\"outbound\":\"direct\"}]"),
        concat!("JSON خام sing-box. پیش از کلیدهای بالا اجرا می\u{200c}شود. ", "نمونه\u{654} قواعد: [{\"domain_suffix\":[\"openai.com\"],\"outbound\":\"direct\"}]"),
        concat!("原样的 sing-box JSON。它们先于上方开关执行。 ", "规则示例：[{\"domain_suffix\":[\"openai.com\"],\"outbound\":\"direct\"}]"),
        concat!("JSON sing-box як ёсць. Выконваецца перад пераключальнікамі вышэй. ", "Прыклад правіл: [{\"domain_suffix\":[\"openai.com\"],\"outbound\":\"direct\"}]"),
        concat!("JSON sing-box tak, jak je. Provádí se před přepínači výše. ", "Příklad pravidel: [{\"domain_suffix\":[\"openai.com\"],\"outbound\":\"direct\"}]"),
        concat!("JSON sing-box tak, ako je. Vykonáva sa pred prepínačmi vyššie. ", "Príklad pravidiel: [{\"domain_suffix\":[\"openai.com\"],\"outbound\":\"direct\"}]"),
    ];
    T[ix(lang)]
}

pub fn routing_profile_rule_count(lang: L, n: i64) -> String {
    match lang {
        L::Russian | L::System => format!("правил: {n}"),
        L::English => format!("{n} rules"),
        L::Persian => format!("{n} قاعده"),
        L::Chinese => format!("{n} 条规则"),
        L::Belarusian => format!("{n} {}", ru_plural(n, "правіла", "правілы", "правіл")),
        L::Czech => format!("{n} {}", cs_plural(n, "pravidlo", "pravidla", "pravidel")),
        L::Slovak => format!("{n} {}", sk_plural(n, "pravidlo", "pravidlá", "pravidiel")),
    }
}

pub fn eta_left(lang: L, sec: i64) -> String {
    match lang {
        L::Russian | L::System => format!("осталось ~{}", eta_text(sec, "с", "мин", "ч")),
        L::English => format!("~{} left", eta_text(sec, "s", "min", "h")),
        L::Persian => format!("حدود {} مانده", eta_text(sec, "ثانیه", "دقیقه", "ساعت")),
        L::Chinese => format!("剩余约 {}", eta_text(sec, "秒", "分钟", "小时")),
        L::Belarusian => format!("засталося ~{}", eta_text(sec, "с", "хв", "г")),
        L::Czech => format!("zbývá ~{}", eta_text(sec, "s", "min", "h")),
        L::Slovak => format!("zostáva ~{}", eta_text(sec, "s", "min", "h")),
    }
}

pub fn apps_count(lang: L, n: i64) -> String {
    match lang {
        L::Russian | L::System => format!("{n} {}", ru_plural(n, "приложение", "приложения", "приложений")),
        L::English => {
            if n == 1 {
                "1 app".to_string()
            } else {
                format!("{n} apps")
            }
        }
        L::Persian => {
            if n == 1 {
                "۱ برنامه".to_string()
            } else {
                format!("{n} برنامه")
            }
        }
        L::Chinese => format!("{n} 个应用"),
        L::Belarusian => format!("{n} {}", ru_plural(n, "праграма", "праграмы", "праграм")),
        L::Czech => format!("{n} {}", cs_plural(n, "aplikace", "aplikace", "aplikací")),
        L::Slovak => format!("{n} {}", sk_plural(n, "aplikácia", "aplikácie", "aplikácií")),
    }
}

fn with_apps(lang: L, n: i64, t: [&str; 7]) -> String {
    t[ix(lang)].replace("{apps}", &apps_count(lang, n))
}

pub fn use_yptun_count(lang: L, n: i64) -> String {
    with_apps(lang, n, [
        "{apps} через YPtun",
        "{apps} use YPtun",
        "{apps} از YPtun استفاده می\u{200c}کنند",
        "{apps}使用 YPtun",
        "Выкарыстоўвае YPtun: {apps}",
        "Používá YPtun: {apps}",
        "Používa YPtun: {apps}",
    ])
}

pub fn only_use_yptun_count(lang: L, n: i64) -> String {
    with_apps(lang, n, [
        "Только {apps} через YPtun",
        "Only {apps} use YPtun",
        "فقط {apps} از YPtun استفاده می\u{200c}کنند",
        "仅{apps}使用 YPtun",
        "YPtun выкарыстоўвае толькі: {apps}",
        "YPtun používá pouze: {apps}",
        "YPtun používa iba: {apps}",
    ])
}

pub fn only_count(lang: L, n: i64) -> String {
    with_apps(lang, n, ["Только {apps}", "Only {apps}", "فقط {apps}", "仅{apps}", "Толькі: {apps}", "Pouze: {apps}", "Iba: {apps}"])
}

pub fn bypassed_count(lang: L, n: i64) -> String {
    with_apps(lang, n, [
        "{apps} в обход",
        "{apps} bypassed",
        "{apps} دور زده\u{200c}شده",
        "{apps}被绕过",
        "У абыходзе: {apps}",
        "Obchází se: {apps}",
        "Obchádza sa: {apps}",
    ])
}

pub fn bypass_yptun_count(lang: L, n: i64) -> String {
    with_apps(lang, n, [
        "{apps} в обход YPtun",
        "{apps} bypass YPtun",
        "{apps} YPtun را دور می\u{200c}زنند",
        "{apps}绕过 YPtun",
        "YPtun абыходзіць: {apps}",
        "YPtun obchází: {apps}",
        "YPtun obchádza: {apps}",
    ])
}

pub fn ru_bypass_matched_by_package(lang: L, n: i64) -> String {
    with_apps(lang, n, [
        "{apps} по пакету",
        "{apps} matched by package",
        "{apps} بر اساس بسته",
        "按包名匹配到{apps}",
        "Супала па пакеце: {apps}",
        "Podle balíčku odpovídá: {apps}",
        "Podľa balíka sa zhoduje: {apps}",
    ])
}

pub fn ru_bypass_already_selected(lang: L, n: i64) -> String {
    with_apps(lang, n, [
        "{apps} уже выбрано",
        "{apps} already selected",
        "{apps} از قبل انتخاب شده",
        "已选择{apps}",
        "Ужо выбрана: {apps}",
        "Již vybráno: {apps}",
        "Už vybrané: {apps}",
    ])
}

pub fn ru_bypass_auto_bypassed(lang: L, n: i64) -> String {
    with_apps(lang, n, [
        "{apps} авто-обход",
        "{apps} auto-bypassed",
        "{apps} دور زدن خودکار",
        "自动绕过{apps}",
        "Аўтаматычна ў абыходзе: {apps}",
        "Automaticky obcházeno: {apps}",
        "Automaticky obchádzané: {apps}",
    ])
}

pub fn hours_short(lang: L, n: i64) -> String {
    const NEVER: [&str; 7] = ["Никогда", "Never", "هرگز", "从不", "Ніколі", "Nikdy", "Nikdy"];
    if n == 0 {
        return NEVER[ix(lang)].to_string();
    }
    match lang {
        L::Russian | L::System => format!("{n} ч"),
        L::English => format!("{n}h"),
        L::Persian => format!("{n} ساعت"),
        L::Chinese => format!("{n} 小时"),
        L::Belarusian => format!("{n} г"),
        L::Czech | L::Slovak => format!("{n} h"),
    }
}

pub fn app_icon_names(lang: L) -> [&'static str; 10] {
    const T: [[&str; 10]; 7] = [
        ["Классика", "Океан", "Закат", "Мята", "Фиалка", "Золото", "Небо", "Неон", "Солнце", "Светлая"],
        ["Classic", "Ocean", "Sunset", "Mint", "Violet", "Gold", "Sky", "Neon", "Sun", "Light"],
        ["کلاسیک", "اقیانوس", "غروب", "نعنا", "بنفش", "طلایی", "آسمان", "نئون", "خورشید", "روشن"],
        ["经典", "海洋", "日落", "薄荷", "紫罗兰", "金色", "天空", "霓虹", "阳光", "浅色"],
        ["Класічны", "Акіян", "Захад сонца", "Мята", "Фіялетавы", "Залаты", "Неба", "Неон", "Сонца", "Светлы"],
        ["Klasická", "Oceán", "Západ slunce", "Máta", "Fialová", "Zlatá", "Obloha", "Neon", "Slunce", "Světlá"],
        ["Klasická", "Oceán", "Západ slnka", "Mäta", "Fialová", "Zlatá", "Obloha", "Neón", "Slnko", "Svetlá"],
    ];
    T[ix(lang)]
}

const EXPIRED: [&str; 7] = [
    "истекла {date}",
    "expired {date}",
    "منقضی\u{200c}شده {date}",
    "已于 {date} 过期",
    "скончылася {date}",
    "skončilo {date}",
    "skončilo {date}",
];
const UNTIL: [&str; 7] = ["до {date}", "until {date}", "تا {date}", "至 {date}", "да {date}", "do {date}", "do {date}"];
const UNTIL_TODAY: [&str; 7] = [
    "до {date} · сегодня",
    "until {date} · today",
    "تا {date} · امروز",
    "至 {date} · 今天",
    "да {date} · сёння",
    "do {date} · dnes",
    "do {date} · dnes",
];

pub fn subscription_expiry(lang: L, date_time: &str, days_left: i64) -> String {
    let t = if days_left < 0 { EXPIRED } else { UNTIL };
    t[ix(lang)].replace("{date}", date_time)
}

pub fn subscription_expiry_full(lang: L, date_time: &str, days_left: i64) -> String {
    if days_left < 0 {
        return EXPIRED[ix(lang)].replace("{date}", date_time);
    }
    if days_left == 0 {
        return UNTIL_TODAY[ix(lang)].replace("{date}", date_time);
    }
    let d = days_left;
    match lang {
        L::Russian | L::System => format!("до {date_time} · через {d} дн."),
        L::English => format!("until {date_time} · in {d} days"),
        L::Persian => format!("تا {date_time} · {d} روز دیگر"),
        L::Chinese => format!("至 {date_time} · 剩 {d} 天"),
        L::Belarusian => format!("да {date_time} · праз {d} {}", ru_plural(d, "дзень", "дні", "дзён")),
        L::Czech => format!("do {date_time} · za {d} {}", cs_plural(d, "den", "dny", "dní")),
        L::Slovak => format!("do {date_time} · o {d} {}", sk_plural(d, "deň", "dni", "dní")),
    }
}

/// Blank host gives just the name.
pub fn add_detected_subscription(lang: L, host: &str) -> String {
    const NAME: [&str; 7] = ["Подписка", "Subscription", "اشتراک", "订阅", "Падпіска", "Předplatné", "Predplatné"];
    const WITH_HOST: [&str; 7] = [
        "Подписка · {host}",
        "Subscription · {host}",
        "اشتراک · {host}",
        "订阅 · {host}",
        "Падпіска · {host}",
        "Předplatné · {host}",
        "Predplatné · {host}",
    ];
    if host.trim().is_empty() {
        NAME[ix(lang)].to_string()
    } else {
        WITH_HOST[ix(lang)].replace("{host}", host)
    }
}

pub fn subscription_expires_in(lang: L, date: &str, days: i64) -> String {
    if days < 0 {
        const ENDED: [&str; 7] = [
            "Закончилась {date}",
            "Ended {date}",
            "پایان یافت {date}",
            "已于 {date} 到期",
            "Скончылася {date}",
            "Skončilo {date}",
            "Skončilo {date}",
        ];
        return ENDED[ix(lang)].replace("{date}", date);
    }
    match lang {
        L::Russian | L::System => format!("До {date} · ещё {days} {}", ru_plural(days, "день", "дня", "дней")),
        L::English => format!("Until {date} · {days} {} left", if days == 1 { "day" } else { "days" }),
        L::Persian => format!("تا {date} · {days} روز مانده"),
        L::Chinese => format!("至 {date} · 剩余 {days} 天"),
        L::Belarusian => format!("Да {date} · засталося {days} {}", ru_plural(days, "дзень", "дні", "дзён")),
        L::Czech => format!("Do {date} · zbývá {days} {}", cs_plural(days, "den", "dny", "dní")),
        L::Slovak => format!("Do {date} · zostáva {days} {}", sk_plural(days, "deň", "dni", "dní")),
    }
}

pub fn subscription_servers_count(lang: L, total: i64, alive: Option<i64>) -> String {
    const TOTAL: [&str; 7] = ["Серверов: ", "Servers: ", "سرورها: ", "服务器：", "Серверы: ", "Servery: ", "Servery: "];
    const ALIVE: [&str; 7] = [
        " · отвечают ",
        " · answering ",
        " · پاسخ\u{200c}گو ",
        " · 响应 ",
        " · адказвае ",
        " · odpovídá ",
        " · odpovedá ",
    ];
    let i = ix(lang);
    let mut s = format!("{}{total}", TOTAL[i]);
    if let Some(a) = alive {
        s.push_str(ALIVE[i]);
        s.push_str(&a.to_string());
    }
    s
}
