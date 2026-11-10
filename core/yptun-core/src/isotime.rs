//! Dependency-free ISO-8601 ⇄ epoch helpers for subscription dates (port of `IsoTime`). UTC only:
//! a subscription expiry has day granularity.

const MS_PER_DAY: i64 = 86_400_000;

/// Days since 1970-01-01 for a proleptic-Gregorian date (Howard Hinnant's algorithm).
pub fn days_from_civil(year: i32, month: u32, day: u32) -> i64 {
    let y = if month <= 2 { year as i64 - 1 } else { year as i64 };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let mp = if month > 2 { month as i64 - 3 } else { month as i64 + 9 };
    let doy = (153 * mp + 2) / 5 + day as i64 - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

/// days-since-epoch → (year, month, day).
pub fn civil_from_days(days: i64) -> (i32, u32, u32) {
    let z = days + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let year = if month <= 2 { y + 1 } else { y } as i32;
    (year, month, day)
}

/// `2099-05-03T20:59:00.000Z` (space separator, no time, fractions, `±hh:mm`/`±hhmm`/`Z` offsets) → epoch-ms.
pub fn parse_iso_to_epoch_ms(iso: Option<&str>) -> Option<i64> {
    let s = iso?.trim();
    if s.len() < 10 || !s.is_char_boundary(10) {
        return None;
    }
    let year: i32 = s.get(0..4)?.parse().ok()?;
    let month: u32 = s.get(5..7)?.parse().ok()?;
    let day: u32 = s.get(8..10)?.parse().ok()?;
    let (mut hour, mut minute, mut second, mut offset_minutes) = (0i64, 0i64, 0i64, 0i64);
    let sep = s.as_bytes().get(10).copied();
    if s.len() > 11 && matches!(sep, Some(b'T') | Some(b't') | Some(b' ')) {
        let mut rest = s.get(11..)?.to_string();
        if rest.ends_with('Z') || rest.ends_with('z') {
            rest.pop();
        } else if let Some(sign) = rest.rfind(['+', '-']).filter(|i| *i > 0) {
            let zone = rest[sign + 1..].replace(':', "");
            let negative = rest.as_bytes()[sign] == b'-';
            rest.truncate(sign);
            if zone.len() >= 2 {
                let oh: i64 = zone.get(0..2)?.parse().ok()?;
                let om: i64 = if zone.len() >= 4 { zone.get(2..4)?.parse().ok()? } else { 0 };
                offset_minutes = if negative { -1 } else { 1 } * (oh * 60 + om);
            }
        }
        let hms: Vec<&str> = rest.split('.').next().unwrap_or("").split(':').collect();
        hour = hms.first().and_then(|v| v.parse().ok()).unwrap_or(0);
        minute = hms.get(1).and_then(|v| v.parse().ok()).unwrap_or(0);
        second = hms.get(2).and_then(|v| v.parse().ok()).unwrap_or(0);
    }
    let sec = days_from_civil(year, month, day) * 86_400 + hour * 3600 + minute * 60 + second - offset_minutes * 60;
    Some(sec * 1000)
}

/// `DD.MM.YYYY` (UTC).
pub fn format_date(epoch_ms: i64) -> String {
    let (y, m, d) = civil_from_days(epoch_ms.div_euclid(MS_PER_DAY));
    format!("{d:02}.{m:02}.{y}")
}

/// `DD.MM.YYYY HH:MM:SS` (UTC); pass `epoch_ms + utc_offset_ms` for local time.
pub fn format_date_time(epoch_ms: i64) -> String {
    let sec = epoch_ms.rem_euclid(MS_PER_DAY) / 1000;
    format!("{} {:02}:{:02}:{:02}", format_date(epoch_ms), sec / 3600, (sec % 3600) / 60, sec % 60)
}

/// `DD.MM.YYYY HH:MM:SS` in the device's time zone, so subscription dates match the rest of the app
/// (the panel sends an absolute instant; UTC was 3 h off for MSK users). `utc_offset_seconds` is the
/// zone's offset AT `epoch_ms` (Swift `TimeZone.current.secondsFromGMT(for:)`).
pub fn format_local_date_time(epoch_ms: i64, utc_offset_seconds: i32) -> String {
    format_date_time(epoch_ms + utc_offset_seconds as i64 * 1000)
}

/// [`format_date`] in the device's time zone (matches [`format_local_date_time`]).
pub fn format_local_date(epoch_ms: i64, utc_offset_seconds: i32) -> String {
    format_date(epoch_ms + utc_offset_seconds as i64 * 1000)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_and_formats() {
        let ms = parse_iso_to_epoch_ms(Some("2099-05-03T20:59:00.000Z")).unwrap();
        assert_eq!(format_date_time(ms), "03.05.2099 20:59:00");
        assert_eq!(parse_iso_to_epoch_ms(Some("2026-01-01T03:00:00+03:00")), parse_iso_to_epoch_ms(Some("2026-01-01")));
        assert_eq!(parse_iso_to_epoch_ms(Some("1970-01-01 00:00:01")), Some(1000));
        assert!(parse_iso_to_epoch_ms(Some("soon")).is_none());
        assert_eq!(civil_from_days(days_from_civil(2000, 2, 29)), (2000, 2, 29));
    }
}
