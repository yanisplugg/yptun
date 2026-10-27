use anyhow::{Result, bail};

use crate::wire_protocol::PATH_ECHO_PREFIX;

pub const PANEL_RESTART_NOTICE: &[u8] = b"\xffCSQTT_PANEL_RESTART_V1\x00\x91\x7d\x03\xa8";
pub const STREAM_REPAIR_PREFIX: &[u8] = b"\xffCSQTT_STREAM_REPAIR_V1";
pub const STREAM_ALIVE_PREFIX: &[u8] = b"\xffCSQTT_STREAM_ALIVE_V1";
const MAX_STREAM_WORKERS: u16 = 100;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConfigResponse {
    Config(String),
    NoConfig,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StreamRepairCommand {
    pub sequence: u64,
    pub desired_count: u16,
    pub worker_ids: Vec<u16>,
}

pub fn config_request(
    local_port: &str,
    password: &str,
    generation_id: u64,
    salt: &str,
    worker_id: usize,
    desired_count: Option<usize>,
) -> String {
    let desired_count = desired_count.map_or_else(String::new, |value| value.to_string());
    format!("GETCONF:{local_port}|{password}|{generation_id}|{salt}|{worker_id}|{desired_count}")
}

pub fn parse_config_response(response: &[u8]) -> Result<ConfigResponse> {
    let response = std::str::from_utf8(response)?;
    if response == "NOCONF" {
        return Ok(ConfigResponse::NoConfig);
    }
    if let Some(reason) = response.strip_prefix("DENIED:") {
        match reason {
            "wrong_password" => bail!("FATAL_AUTH: неверный пароль подключения"),
            "expired" => bail!("FATAL_AUTH: срок действия пароля истёк"),
            "deactivated" => bail!("FATAL_AUTH: аккаунт деактивирован"),
            "protocol_mismatch" => bail!(
                "FATAL_PROTOCOL: клиент и сервер используют разные версии протокола; выполните деплой из этой версии приложения"
            ),
            "invalid_worker_count" => bail!("превышен лимит потоков"),
            _ => bail!("доступ отклонён сервером ({reason})"),
        }
    }
    if response.starts_with("TUNCONF:") {
        return Ok(ConfigResponse::Config(response.to_owned()));
    }
    bail!("unexpected GETCONF response")
}

pub fn is_config_response(response: &[u8]) -> bool {
    [
        b"TUNCONF:".as_slice(),
        b"NOCONF".as_slice(),
        b"DENIED:".as_slice(),
    ]
    .iter()
    .any(|prefix| response.starts_with(prefix))
}

pub fn is_control_response(response: &[u8]) -> bool {
    if is_panel_restart_notice(response) {
        return true;
    }
    [
        b"TUNCONF:".as_slice(),
        b"NOCONF".as_slice(),
        b"DENIED:".as_slice(),
        b"READY_OK".as_slice(),
        b"OK:disconnected".as_slice(),
        b"ROUTING:".as_slice(),
        b"TUNWORKERS:".as_slice(),
        b"WORKERS:".as_slice(),
        STREAM_REPAIR_PREFIX,
        STREAM_ALIVE_PREFIX,
        PATH_ECHO_PREFIX,
    ]
    .iter()
    .any(|prefix| response.starts_with(prefix))
}

pub fn workers_request(password: &str, desired_count: usize) -> String {
    format!("GETWORKERS:{password}|{desired_count}")
}

pub fn parse_tunworkers_response(payload: &[u8]) -> Option<(usize, usize)> {
    let text = std::str::from_utf8(payload).ok()?;
    if let Some(rest) = text.strip_prefix("TUNWORKERS:") {
        let mut parts = rest.split(':');
        let agreed = parts.next()?.trim().parse::<usize>().ok()?;
        let server_limit = parts
            .next()
            .and_then(|v| v.trim().parse::<usize>().ok())
            .unwrap_or(0);
        return Some((agreed, server_limit));
    }
    if let Some(rest) = text.strip_prefix("WORKERS:") {
        let limit = rest.trim().parse::<usize>().ok()?;
        return Some((limit, limit));
    }
    None
}

pub fn is_panel_restart_notice(response: &[u8]) -> bool {
    response == PANEL_RESTART_NOTICE
}

pub fn disconnect_request(password: &str, salt: &str) -> String {
    format!("DISCONNECT:{password}|{salt}")
}

pub fn parse_stream_repair(payload: &[u8]) -> Option<StreamRepairCommand> {
    parse_stream_command(payload, STREAM_REPAIR_PREFIX)
}

pub fn parse_stream_alive(payload: &[u8]) -> Option<StreamRepairCommand> {
    parse_stream_command(payload, STREAM_ALIVE_PREFIX)
}

fn parse_stream_command(payload: &[u8], prefix: &[u8]) -> Option<StreamRepairCommand> {
    let rest = payload.strip_prefix(prefix)?;
    if rest.len() < 11 {
        return None;
    }
    let sequence = u64::from_be_bytes(rest[0..8].try_into().ok()?);
    let desired_count = u16::from_be_bytes(rest[8..10].try_into().ok()?);
    let count = usize::from(rest[10]);
    let ids = &rest[11..];
    if sequence == 0
        || desired_count == 0
        || desired_count > MAX_STREAM_WORKERS
        || ids.len() != count.saturating_mul(2)
    {
        return None;
    }
    let mut worker_ids = Vec::with_capacity(count);
    for chunk in ids.chunks_exact(2) {
        let worker_id = u16::from_be_bytes(chunk.try_into().ok()?);
        if worker_id == 0 || worker_id > desired_count {
            return None;
        }
        worker_ids.push(worker_id);
    }
    (!worker_ids.is_empty()).then_some(StreamRepairCommand {
        sequence,
        desired_count,
        worker_ids,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn request_matches_go_contract() {
        let req1 = config_request("9000", "pass1234567890ab", 7, "salt", 4, None);
        assert_eq!(req1, "GETCONF:9000|pass1234567890ab|7|salt|4|");

        let req2 = config_request("9000", "pass1234567890ab", 7, "salt", 4, Some(36));
        assert_eq!(req2, "GETCONF:9000|pass1234567890ab|7|salt|4|36");
    }

    #[test]
    fn response_matches_go_contract() {
        assert_eq!(
            parse_config_response(b"TUNCONF:10.66.66.2:1.1.1.1").unwrap(),
            ConfigResponse::Config("TUNCONF:10.66.66.2:1.1.1.1".into())
        );
        assert!(
            parse_config_response(b"DENIED:wrong_password")
                .unwrap_err()
                .to_string()
                .contains("FATAL_AUTH")
        );
        assert!(
            parse_config_response(b"DENIED:protocol_mismatch")
                .unwrap_err()
                .to_string()
                .contains("FATAL_PROTOCOL")
        );
    }

    #[test]
    fn control_responses_never_enter_tun() {
        for value in [
            b"TUNCONF:10.66.67.3:1.1.1.1".as_slice(),
            b"NOCONF".as_slice(),
            b"DENIED:expired".as_slice(),
            b"READY_OK".as_slice(),
            b"OK:disconnected".as_slice(),
            b"TUNWORKERS:20:20".as_slice(),
            b"WORKERS:20".as_slice(),
            PANEL_RESTART_NOTICE,
            STREAM_REPAIR_PREFIX,
            STREAM_ALIVE_PREFIX,
        ] {
            assert!(is_control_response(value));
        }
        assert!(!is_control_response(&[0x45, 0, 0, 20]));
    }

    #[test]
    fn parses_tunworkers_response() {
        assert_eq!(
            parse_tunworkers_response(b"TUNWORKERS:20:20"),
            Some((20, 20))
        );
        assert_eq!(parse_tunworkers_response(b"TUNWORKERS:44:0"), Some((44, 0)));
        assert_eq!(parse_tunworkers_response(b"WORKERS:15"), Some((15, 15)));
        assert_eq!(parse_tunworkers_response(b"TUNCONF:10.0.0.1"), None);
    }

    #[test]
    fn panel_restart_notice_requires_an_exact_match() {
        assert!(is_panel_restart_notice(PANEL_RESTART_NOTICE));
        assert!(!is_panel_restart_notice(b"CSQTT_PANEL_RESTART_V1"));
        let mut modified = PANEL_RESTART_NOTICE.to_vec();
        modified[1] ^= 1;
        assert!(!is_panel_restart_notice(&modified));
    }

    #[test]
    fn config_wait_ignores_tunneled_packets_and_other_controls() {
        assert!(is_config_response(b"TUNCONF:10.66.67.3:1.1.1.1"));
        assert!(is_config_response(b"NOCONF"));
        assert!(is_config_response(b"DENIED:expired"));
        assert!(!is_config_response(b"READY_OK"));
        assert!(!is_config_response(&[0x45, 0, 0, 20]));
        assert!(!is_config_response(&[0xff, 0xfe]));
    }

    #[test]
    fn parses_stream_repair_command() {
        let mut payload = STREAM_REPAIR_PREFIX.to_vec();
        payload.extend_from_slice(&9u64.to_be_bytes());
        payload.extend_from_slice(&36u16.to_be_bytes());
        payload.push(2);
        payload.extend_from_slice(&14u16.to_be_bytes());
        payload.extend_from_slice(&28u16.to_be_bytes());
        assert_eq!(
            parse_stream_repair(&payload),
            Some(StreamRepairCommand {
                sequence: 9,
                desired_count: 36,
                worker_ids: vec![14, 28],
            })
        );
        assert!(parse_stream_alive(&payload).is_none());
        payload[STREAM_REPAIR_PREFIX.len() + 10] = 0;
        assert!(parse_stream_repair(&payload).is_none());
    }

    #[test]
    fn malformed_utf8_returns_error() {
        assert!(parse_config_response(&[0xff, 0xfe]).is_err());
    }

    #[test]
    fn no_config_response_is_preserved() {
        assert_eq!(
            parse_config_response(b"NOCONF").unwrap(),
            ConfigResponse::NoConfig
        );
    }

    #[test]
    fn auth_denied_reasons_distinguish_fatal_from_retryable() {
        for response in [
            b"DENIED:wrong_password".as_slice(),
            b"DENIED:expired".as_slice(),
            b"DENIED:deactivated".as_slice(),
        ] {
            assert!(
                parse_config_response(response)
                    .unwrap_err()
                    .to_string()
                    .contains("FATAL_AUTH")
            );
        }
        for response in [
            b"DENIED:invalid_epoch".as_slice(),
            b"DENIED:invalid_worker".as_slice(),
            b"DENIED:server_busy".as_slice(),
            b"DENIED:unknown".as_slice(),
        ] {
            let error = parse_config_response(response).unwrap_err().to_string();
            assert!(!error.contains("FATAL_AUTH"));
            assert!(!error.contains("FATAL_PROTOCOL"));
        }
    }

    #[test]
    fn test_two_stage_handshake_requests_and_responses() {
        let req1 = config_request("45000", "pass123", 789, "salt99", 1, None);
        assert_eq!(req1, "GETCONF:45000|pass123|789|salt99|1|");

        let req2 = workers_request("pass123", 25);
        assert_eq!(req2, "GETWORKERS:pass123|25");

        let res1 = parse_tunworkers_response(b"TUNWORKERS:10:10");
        assert_eq!(res1, Some((10, 10)));

        let res2 = parse_tunworkers_response(b"TUNWORKERS:25:0");
        assert_eq!(res2, Some((25, 0)));

        let res_legacy = parse_tunworkers_response(b"WORKERS:15");
        assert_eq!(res_legacy, Some((15, 15)));

        assert!(is_control_response(b"TUNWORKERS:25:0"));
        assert!(is_control_response(b"TUNCONF:test"));
    }
}
