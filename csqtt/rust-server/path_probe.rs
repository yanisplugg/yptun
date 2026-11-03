#![allow(dead_code)]

use crate::wire_protocol::PATH_ECHO_PREFIX;

const PATH_PROBE_PREFIX: &[u8] = b"\xffCSQTT_PATH_PROBE_V1";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PathProbe {
    pub nonce: u64,
}

pub fn parse_path_probe(payload: &[u8]) -> Option<PathProbe> {
    let rest = payload.strip_prefix(PATH_PROBE_PREFIX)?;
    if rest.len() != 12 {
        return None;
    }
    Some(PathProbe {
        nonce: u64::from_be_bytes(rest[..8].try_into().ok()?),
    })
}

pub fn path_echo_payload(nonce: u64) -> Vec<u8> {
    let mut payload = Vec::with_capacity(PATH_ECHO_PREFIX.len() + 8);
    payload.extend_from_slice(PATH_ECHO_PREFIX);
    payload.extend_from_slice(&nonce.to_be_bytes());
    payload
}
