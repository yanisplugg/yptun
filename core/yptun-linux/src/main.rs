//! Native Linux entrypoint.  This is deliberately independent of the JVM: the Linux package can
//! launch this binary directly while the remaining desktop UI is migrated incrementally.

use std::env;
use std::process::ExitCode;

use yptun_core::desktop_proxy::split_proxy_host_port;
use yptun_core::http_proxy_bridge::{http_port_for, HttpProxyBridge};

fn usage() -> ! {
    eprintln!("usage: yptun-linux proxy <socks-host:port>");
    std::process::exit(2)
}

fn main() -> ExitCode {
    let mut args = env::args().skip(1);
    let mode = args.next().unwrap_or_else(|| usage());
    if mode != "proxy" {
        usage();
    }
    let endpoint = args.next().unwrap_or_else(|| usage());
    if args.next().is_some() {
        usage();
    }
    let (host, socks_port) = match split_proxy_host_port(&endpoint) {
        Some((host, port)) => (host, port as u16),
        None => {
            eprintln!("invalid SOCKS endpoint: {endpoint}");
            return ExitCode::from(2);
        }
    };
    let bridge_port = http_port_for(socks_port as i32) as u16;
    let bridge = HttpProxyBridge::new("127.0.0.1", bridge_port, &host, socks_port, |line| eprintln!("{line}"));
    if !bridge.start() {
        return ExitCode::from(1);
    }
    eprintln!("YPtun Rust proxy listening on 127.0.0.1:{bridge_port} -> {endpoint}");
    let _ = std::io::stdin().read_line(&mut String::new());
    bridge.stop();
    ExitCode::SUCCESS
}
