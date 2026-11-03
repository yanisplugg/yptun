use std::net::IpAddr;

pub type IpFilter<'a> = Box<dyn Fn(&IpAddr, &IpAddr) -> bool + Send + Sync + 'a>;

pub struct IpFilters<'a> {
    filters: Vec<IpFilter<'a>>,
}

impl<'a> Default for IpFilters<'a> {
    fn default() -> Self {
        Self::new()
    }
}

impl<'a> IpFilters<'a> {
    pub fn new() -> Self {
        Self {
            filters: Default::default(),
        }
    }

    pub fn with_non_broadcast() -> Self {
        macro_rules! non_broadcast {
            ($addr:ident) => {
                match $addr {
                    IpAddr::V4(a) => !(a.is_broadcast() || a.is_multicast() || a.is_unspecified()),
                    IpAddr::V6(a) => !(a.is_multicast() || a.is_unspecified()),
                }
            };
        }
        Self {
            filters: vec![Box::new(|src, dst| {
                non_broadcast!(src) && non_broadcast!(dst)
            })],
        }
    }

    pub fn add(&mut self, filter: IpFilter<'a>) {
        self.filters.push(filter);
    }

    pub fn add_fn<F>(&mut self, filter: F)
    where
        F: Fn(&IpAddr, &IpAddr) -> bool + Send + Sync + 'a,
    {
        self.filters.push(Box::new(filter));
    }

    pub fn add_all<I: IntoIterator<Item = IpFilter<'a>>>(&mut self, filters: I) {
        self.filters.extend(filters);
    }

    pub fn is_allowed(&self, src: &IpAddr, dst: &IpAddr) -> bool {
        self.filters.iter().all(|filter| filter(src, dst))
    }
}
