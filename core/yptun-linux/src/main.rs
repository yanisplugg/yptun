//! Native Linux entrypoint.  This is deliberately independent of the JVM: the Linux package can
//! launch this binary directly while the remaining desktop UI is migrated incrementally.

use std::env;
use std::process::ExitCode;

use eframe::egui;
use yptun_core::desktop_proxy::split_proxy_host_port;
use yptun_core::http_proxy_bridge::{http_port_for, HttpProxyBridge};

fn usage() -> ! {
    eprintln!("usage: yptun-linux proxy <socks-host:port>");
    std::process::exit(2)
}

fn main() -> ExitCode {
    if env::args().nth(1).as_deref() != Some("proxy") {
        let options = eframe::NativeOptions {
            viewport: egui::ViewportBuilder::default()
                .with_inner_size([960.0, 640.0])
                .with_min_inner_size([760.0, 520.0]),
            ..Default::default()
        };
        return match eframe::run_native(
            "YPtun",
            options,
            Box::new(|_| Ok(Box::new(YpTunApp::default()))),
        ) {
            Ok(()) => ExitCode::SUCCESS,
            Err(error) => {
                eprintln!("failed to start YPtun UI: {error}");
                ExitCode::from(1)
            }
        };
    }

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

struct YpTunApp {
    endpoint: String,
    proxy_port: String,
    status: String,
    bridge: Option<HttpProxyBridge>,
}

impl Default for YpTunApp {
    fn default() -> Self {
        Self {
            endpoint: "127.0.0.1:1080".to_owned(),
            proxy_port: "8080".to_owned(),
            status: "Отключено".to_owned(),
            bridge: None,
        }
    }
}

impl eframe::App for YpTunApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        egui::TopBottomPanel::top("topbar").show(ctx, |ui| {
            ui.horizontal(|ui| {
                ui.heading("YPtun");
                ui.separator();
                ui.label("Linux · Rust");
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    ui.label(&self.status);
                });
            });
        });

        egui::SidePanel::left("navigation")
            .resizable(false)
            .default_width(210.0)
            .show(ctx, |ui| {
                ui.add_space(12.0);
                let _ = ui.selectable_label(true, "Подключение");
                let _ = ui.selectable_label(false, "Локации");
                let _ = ui.selectable_label(false, "Маршрутизация");
                let _ = ui.selectable_label(false, "Настройки");
                ui.separator();
                ui.small("Rust runtime");
            });

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.add_space(22.0);
            ui.heading("Подключение");
            ui.add_space(12.0);
            ui.group(|ui| {
                ui.label("SOCKS5 endpoint");
                ui.text_edit_singleline(&mut self.endpoint);
                ui.add_space(8.0);
                ui.horizontal(|ui| {
                    ui.label("HTTP proxy port");
                    ui.add(egui::TextEdit::singleline(&mut self.proxy_port).desired_width(90.0));
                });
                ui.add_space(14.0);
                let connected = self.bridge.is_some();
                if ui
                    .add_sized(
                        [180.0, 38.0],
                        egui::Button::new(if connected { "Отключить" } else { "Подключить" }),
                    )
                    .clicked()
                {
                    if connected {
                        if let Some(bridge) = self.bridge.take() {
                            bridge.stop();
                        }
                        self.status = "Отключено".to_owned();
                    } else {
                        self.start_bridge();
                    }
                }
            });
        });
    }
}

impl YpTunApp {
    fn start_bridge(&mut self) {
        let Some((host, socks_port)) = split_proxy_host_port(&self.endpoint) else {
            self.status = "Неверный SOCKS endpoint".to_owned();
            return;
        };
        let Ok(proxy_port) = self.proxy_port.parse::<i32>() else {
            self.status = "Неверный HTTP-порт".to_owned();
            return;
        };
        if !(1..=65535).contains(&proxy_port) {
            self.status = "HTTP-порт должен быть 1..65535".to_owned();
            return;
        }
        let bridge = HttpProxyBridge::new(
            "127.0.0.1",
            proxy_port as u16,
            &host,
            socks_port as u16,
            |line| eprintln!("{line}"),
        );
        if bridge.start() {
            self.bridge = Some(bridge);
            self.status = format!("HTTP 127.0.0.1:{proxy_port}");
        } else {
            self.status = "Не удалось запустить proxy".to_owned();
        }
    }
}
