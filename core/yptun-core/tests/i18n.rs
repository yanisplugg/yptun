use yptun_core::i18n::{self, AppLanguage};
use yptun_core::i18n_dynamic as d;

fn placeholders(s: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut rest = s;
    while let Some(start) = rest.find('{') {
        let after = &rest[start + 1..];
        match after.find('}') {
            Some(end) => {
                out.push(after[..end].to_string());
                rest = &after[end + 1..];
            }
            None => break,
        }
    }
    out.sort();
    out
}

#[test]
fn every_language_has_the_same_keys() {
    let ru: Vec<&String> = i18n::catalog(AppLanguage::Russian).keys().collect();
    assert!(ru.len() > 700, "only {} keys", ru.len());
    for lang in AppLanguage::ALL {
        let keys: Vec<&String> = i18n::catalog(lang).keys().collect();
        assert_eq!(keys, ru, "{lang:?}");
    }
    assert_eq!(i18n::keys().count(), ru.len());
}

#[test]
fn placeholders_match_russian() {
    for key in i18n::keys() {
        let ru = placeholders(i18n::text(AppLanguage::Russian, key));
        for lang in AppLanguage::ALL {
            assert_eq!(placeholders(i18n::text(lang, key)), ru, "{lang:?} {key}");
        }
    }
}

#[test]
fn language_ids_and_effective() {
    for lang in AppLanguage::ALL {
        assert_eq!(AppLanguage::from_id(Some(lang.id())), lang);
    }
    assert_eq!(AppLanguage::from_id(Some("de")), AppLanguage::System);
    assert_eq!(AppLanguage::from_id(None), AppLanguage::System);
    assert_eq!(AppLanguage::System.effective(AppLanguage::English), AppLanguage::English);
    assert_eq!(AppLanguage::Czech.effective(AppLanguage::English), AppLanguage::Czech);
    assert_eq!(i18n::DEFAULT_SYSTEM_LANGUAGE, AppLanguage::Russian);
}

#[test]
fn lookup_falls_back_and_formats() {
    assert_eq!(i18n::text(AppLanguage::English, "no-such-key"), "no-such-key");
    assert_eq!(i18n::text(AppLanguage::Russian, "languageName"), "Русский");
    assert_eq!(i18n::text(AppLanguage::System, "languageName"), "Русский");
    assert_eq!(i18n::text(AppLanguage::Russian, "navSettings"), "Настройки");
    let key = i18n::keys().find(|k| placeholders(i18n::text(AppLanguage::Russian, k)).len() == 1).unwrap();
    let name = placeholders(i18n::text(AppLanguage::Russian, key)).remove(0);
    let out = i18n::format(AppLanguage::Russian, key, &[(name.as_str(), "42")]);
    assert!(out.contains("42") && !out.contains('{'), "{key}: {out}");
}

#[test]
fn plural_rules() {
    let f = |n| i18n::ru_plural(n, "one", "few", "many");
    assert_eq!(
        [f(1), f(2), f(4), f(5), f(11), f(14), f(21), f(22), f(25), f(111), f(0)],
        ["one", "few", "few", "many", "many", "many", "one", "few", "many", "many", "many"]
    );
    let c = |n| d::cs_plural(n, "one", "few", "many");
    assert_eq!([c(1), c(3), c(5), c(21)], ["one", "few", "many", "many"]);
}

#[test]
fn eta_text_ranges() {
    let e = |s| i18n::eta_text(s, "s", "min", "h");
    assert_eq!(e(0), "1 s");
    assert_eq!(e(89), "89 s");
    assert_eq!(e(90), "2 min");
    assert_eq!(e(5399), "90 min");
    assert_eq!(e(5400), "1 h 30 min");
    assert_eq!(e(7260), "2 h 1 min");
    assert_eq!(d::eta_left(AppLanguage::English, 40), "~40 s left");
    assert_eq!(d::eta_left(AppLanguage::Russian, 600), "осталось ~10 мин");
}

#[test]
fn apps_count_and_wrappers() {
    use AppLanguage::*;
    assert_eq!(d::apps_count(Russian, 1), "1 приложение");
    assert_eq!(d::apps_count(Russian, 3), "3 приложения");
    assert_eq!(d::apps_count(Russian, 11), "11 приложений");
    assert_eq!(d::apps_count(English, 1), "1 app");
    assert_eq!(d::apps_count(English, 2), "2 apps");
    assert_eq!(d::apps_count(Czech, 5), "5 aplikací");
    assert_eq!(d::apps_count(Chinese, 4), "4 个应用");
    assert_eq!(d::bypassed_count(Russian, 2), "2 приложения в обход");
    assert_eq!(d::only_count(English, 1), "Only 1 app");
    assert_eq!(d::use_yptun_count(Slovak, 2), "Používa YPtun: 2 aplikácie");
    assert_eq!(d::ru_bypass_already_selected(Chinese, 3), "已选择3 个应用");
    assert_eq!(d::routing_profile_rule_count(Russian, 7), "правил: 7");
    assert_eq!(d::routing_profile_rule_count(Belarusian, 2), "2 правілы");
}

#[test]
fn subscription_texts() {
    use AppLanguage::*;
    assert_eq!(d::subscription_expires_in(Russian, "12.12", 5), "До 12.12 · ещё 5 дней");
    assert_eq!(d::subscription_expires_in(Russian, "12.12", -1), "Закончилась 12.12");
    assert_eq!(d::subscription_expires_in(English, "12.12", 1), "Until 12.12 · 1 day left");
    assert_eq!(d::subscription_expiry(English, "X", 3), "until X");
    assert_eq!(d::subscription_expiry(English, "X", -3), "expired X");
    assert_eq!(d::subscription_expiry_full(Russian, "X", 0), "до X · сегодня");
    assert_eq!(d::subscription_expiry_full(Russian, "X", 4), "до X · через 4 дн.");
    assert_eq!(d::subscription_expiry_full(Czech, "X", 2), "do X · za 2 dny");
    assert_eq!(d::subscription_servers_count(Russian, 12, Some(9)), "Серверов: 12 · отвечают 9");
    assert_eq!(d::subscription_servers_count(Russian, 12, None), "Серверов: 12");
    assert_eq!(d::add_detected_subscription(Russian, "  "), "Подписка");
    assert_eq!(d::add_detected_subscription(English, "a.b"), "Subscription · a.b");
}

#[test]
fn misc_texts() {
    use AppLanguage::*;
    assert_eq!(d::hours_short(Russian, 0), "Никогда");
    assert_eq!(d::hours_short(English, 3), "3h");
    assert_eq!(d::domain_strategy_name(English, "ipv4_only"), "IPv4 only");
    assert_eq!(d::domain_strategy_name(Russian, "as_is"), "as_is");
    for lang in AppLanguage::ALL {
        assert_eq!(d::app_icon_names(lang).len(), 10);
        assert!(d::sb_routing_advanced_desc(lang).contains(r#""outbound":"direct""#));
    }
    assert_eq!(d::app_icon_names(Russian)[0], "Классика");
}
