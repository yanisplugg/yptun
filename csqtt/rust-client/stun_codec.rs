// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

use anyhow::{Result, bail};
use std::ops::Range;

pub const HEADER_SIZE: usize = 20;
pub const MAGIC_COOKIE: u32 = 0x2112_a442;
pub const ATTR_FINGERPRINT: u16 = 0x8028;
const FINGERPRINT_XOR: u32 = 0x5354_554e;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Class {
    Request,
    Indication,
    Success,
    Error,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Attribute<'a> {
    pub kind: u16,
    pub value: &'a [u8],
    pub header_start: usize,
    pub value_range: Range<usize>,
}

#[derive(Clone)]
pub struct Message<'a> {
    raw: &'a [u8],
    kind: u16,
    transaction: [u8; 12],
    end: usize,
}

impl<'a> Message<'a> {
    pub fn decode(raw: &'a [u8]) -> Result<Self> {
        if raw.len() < HEADER_SIZE {
            bail!("STUN header truncated");
        }
        let kind = u16::from_be_bytes([raw[0], raw[1]]);
        if kind & 0xc000 != 0 {
            bail!("STUN type has non-zero leading bits");
        }
        if kind == 0 {
            bail!("STUN type zero is reserved");
        }
        if raw[4..8] != MAGIC_COOKIE.to_be_bytes() {
            bail!("STUN magic cookie mismatch");
        }
        let declared = u16::from_be_bytes([raw[2], raw[3]]) as usize;
        if !declared.is_multiple_of(4) {
            bail!("STUN message length is not aligned");
        }
        let end = HEADER_SIZE
            .checked_add(declared)
            .ok_or_else(|| anyhow::anyhow!("STUN message length overflow"))?;
        if raw.len() != end {
            bail!("STUN datagram length mismatch");
        }
        let mut position = HEADER_SIZE;
        while position < end {
            if end - position < 4 {
                bail!("STUN attribute header truncated");
            }
            let length = u16::from_be_bytes([raw[position + 2], raw[position + 3]]) as usize;
            let value_start = position + 4;
            let padded = length
                .checked_add(3)
                .map(|value| value & !3)
                .ok_or_else(|| anyhow::anyhow!("STUN attribute length overflow"))?;
            let next = value_start
                .checked_add(padded)
                .ok_or_else(|| anyhow::anyhow!("STUN attribute offset overflow"))?;
            if next > end || value_start + length > end {
                bail!("STUN attribute value truncated");
            }
            position = next;
        }
        let mut transaction = [0u8; 12];
        transaction.copy_from_slice(&raw[8..20]);
        Ok(Self {
            raw,
            kind,
            transaction,
            end,
        })
    }

    #[cfg(test)]
    pub fn kind(&self) -> u16 {
        self.kind
    }

    pub fn class(&self) -> Class {
        match ((self.kind >> 4) & 1) | ((self.kind >> 7) & 2) {
            0 => Class::Request,
            1 => Class::Indication,
            2 => Class::Success,
            3 => Class::Error,
            _ => Class::Error,
        }
    }

    pub fn method(&self) -> u16 {
        (self.kind & 0x000f) | ((self.kind >> 1) & 0x0070) | ((self.kind >> 2) & 0x0f80)
    }

    pub fn transaction(&self) -> [u8; 12] {
        self.transaction
    }

    pub fn attributes(&self) -> Attributes<'a> {
        Attributes {
            raw: self.raw,
            position: HEADER_SIZE,
            end: self.end,
        }
    }

    pub fn attribute(&self, target: u16) -> Option<Attribute<'a>> {
        self.attributes().find(|attribute| attribute.kind == target)
    }

    pub fn fingerprint_valid(&self) -> Option<bool> {
        let attribute = self.attribute(ATTR_FINGERPRINT)?;
        if attribute.value.len() != 4 || attribute.header_start + 8 != self.end {
            return Some(false);
        }
        let expected = crc32fast::hash(&self.raw[..attribute.header_start]) ^ FINGERPRINT_XOR;
        let mut bytes = [0u8; 4];
        bytes.copy_from_slice(attribute.value);
        let actual = u32::from_be_bytes(bytes);
        Some(actual == expected)
    }
}

pub struct Attributes<'a> {
    raw: &'a [u8],
    position: usize,
    end: usize,
}

impl<'a> Iterator for Attributes<'a> {
    type Item = Attribute<'a>;

    fn next(&mut self) -> Option<Self::Item> {
        if self.position >= self.end {
            return None;
        }
        let header_start = self.position;
        let kind = u16::from_be_bytes([self.raw[header_start], self.raw[header_start + 1]]);
        let length =
            u16::from_be_bytes([self.raw[header_start + 2], self.raw[header_start + 3]]) as usize;
        let value_start = header_start + 4;
        let value_end = value_start + length;
        self.position = value_start + ((length + 3) & !3);
        Some(Attribute {
            kind: if kind == 0x8020 { 0x0020 } else { kind },
            value: &self.raw[value_start..value_end],
            header_start,
            value_range: value_start..value_end,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    #[test]
    fn decodes_pion_allocate_vector() {
        let raw =
            hex::decode("000300102112a442000102030405060708090a0b001900041100000080280004c4b8ec87")
                .unwrap();
        let message = Message::decode(&raw).unwrap();
        assert_eq!(message.class(), Class::Request);
        assert_eq!(message.method(), 3);
        assert_eq!(
            message.transaction(),
            [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]
        );
        assert_eq!(message.fingerprint_valid(), Some(true));
        let attributes: Vec<_> = message.attributes().collect();
        assert_eq!(attributes.len(), 2);
        assert_eq!(attributes[0].kind, 0x0019);
        assert_eq!(attributes[0].value, [17, 0, 0, 0]);
    }

    #[test]
    fn rejects_truncated_and_trailing_datagrams() {
        let raw =
            hex::decode("000300102112a442000102030405060708090a0b001900041100000080280004c4b8ec87")
                .unwrap();
        assert!(Message::decode(&raw[..raw.len() - 1]).is_err());
        let mut trailing = raw;
        trailing.push(0);
        assert!(Message::decode(&trailing).is_err());
    }

    #[test]
    fn detects_bad_fingerprint() {
        let mut raw =
            hex::decode("000300102112a442000102030405060708090a0b001900041100000080280004c4b8ec87")
                .unwrap();
        *raw.last_mut().unwrap() ^= 1;
        assert_eq!(
            Message::decode(&raw).unwrap().fingerprint_valid(),
            Some(false)
        );
    }

    proptest! {
        #[test]
        fn arbitrary_input_never_panics(raw in proptest::collection::vec(any::<u8>(), 0..2048)) {
            let _ = Message::decode(&raw);
        }
    }
}
