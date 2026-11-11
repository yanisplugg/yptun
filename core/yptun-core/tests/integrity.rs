use yptun_core::integrity::{
    is_official, matches_official, sha256_hex, signing_sha256, OFFICIAL_SIGNING_SHA256,
};

#[test]
fn sha256_is_lowercase_hex() {
    assert_eq!(
        sha256_hex(b"abc"),
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    );
}

#[test]
fn official_match_ignores_case_and_rejects_missing() {
    assert!(matches_official(Some(OFFICIAL_SIGNING_SHA256)));
    assert!(matches_official(Some(OFFICIAL_SIGNING_SHA256.to_uppercase().as_str())));
    assert!(!matches_official(None));
    assert!(!matches_official(Some("")));
    assert!(!matches_official(Some(sha256_hex(b"abc").as_str())));
}

#[test]
fn first_signature_decides() {
    let none: Vec<Vec<u8>> = Vec::new();
    assert_eq!(signing_sha256(&none), None);
    assert!(!is_official(&none));
    assert_eq!(
        signing_sha256([b"abc".as_slice(), b"zzz".as_slice()]),
        Some(sha256_hex(b"abc"))
    );
    assert!(!is_official([b"abc".as_slice()]));
}
