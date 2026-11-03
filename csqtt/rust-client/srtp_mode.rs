use anyhow::{Result, bail};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SrtpMode {
    Wrap,
    Dtls,
    Auto,
}

impl SrtpMode {
    pub fn parse(value: &str) -> Result<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "wrap" | "wrap-srtp" | "wrap_srtp" | "legacy" => Ok(Self::Wrap),
            "dtls" | "dtls-srtp" | "dtls_srtp" => Ok(Self::Dtls),
            "auto" | "" => Ok(Self::Auto),
            value => bail!("unsupported SRTP mode {value:?}"),
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Wrap => "WRAP-SRTP",
            Self::Dtls => "DTLS-SRTP",
            Self::Auto => "AUTO",
        }
    }

    pub fn uses_dtls(self) -> bool {
        !matches!(self, Self::Wrap)
    }

    pub fn falls_back_to_wrap(self) -> bool {
        matches!(self, Self::Auto)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_every_accepted_spelling() {
        assert_eq!(SrtpMode::parse("wrap-srtp").unwrap(), SrtpMode::Wrap);
        assert_eq!(SrtpMode::parse("WRAP_SRTP").unwrap(), SrtpMode::Wrap);
        assert_eq!(SrtpMode::parse("dtls-srtp").unwrap(), SrtpMode::Dtls);
        assert_eq!(SrtpMode::parse("auto").unwrap(), SrtpMode::Auto);
        assert_eq!(SrtpMode::parse("").unwrap(), SrtpMode::Auto);
        assert!(SrtpMode::parse("nonsense").is_err());
    }

    #[test]
    fn only_wrap_mode_skips_dtls() {
        assert!(!SrtpMode::Wrap.uses_dtls());
        assert!(SrtpMode::Dtls.uses_dtls());
        assert!(SrtpMode::Auto.uses_dtls());
        assert!(SrtpMode::Auto.falls_back_to_wrap());
        assert!(!SrtpMode::Dtls.falls_back_to_wrap());
        assert!(!SrtpMode::Wrap.falls_back_to_wrap());
    }
}
