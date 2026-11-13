//! Native Linux entrypoint.  This is deliberately independent of the JVM: the Linux package can
//! launch this binary directly while the remaining desktop UI is migrated incrementally.

use std::env;
use std::fs;
use std::path::PathBuf;
use std::process::ExitCode;

use eframe::egui;
use serde::{Deserialize, Serialize};
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

#[derive(Serialize, Deserialize, Default)]
struct SavedState {
    endpoint: String,
    proxy_port: String,
    locations: Vec<LocationRow>,
    bypass_lan: bool,
    bypass_russia: bool,
    auto_start: bool,
    system_proxy: bool,
    custom_rules: String,
    tunnel_mode: bool,
    selected_location: usize,
}

struct YpTunApp {
    endpoint: String,
    proxy_port: String,
    status: String,
    bridge: Option<HttpProxyBridge>,
    page: Page,
    locations: Vec<LocationRow>,
    bypass_lan: bool,
    bypass_russia: bool,
    auto_start: bool,
    system_proxy: bool,
    custom_rules: String,
    tunnel_mode: bool,
    selected_location: usize,
    import_text: String,
    show_import: bool,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Page {
    Connection,
    Locations,
    Routing,
    Settings,
}

#[derive(Serialize, Deserialize)]
struct LocationRow {
    name: String,
    endpoint: String,
    enabled: bool,
}

impl Default for YpTunApp {
    fn default() -> Self {
        let defaults = Self {
            endpoint: "127.0.0.1:1080".to_owned(),
            proxy_port: "8080".to_owned(),
            status: "Отключено".to_owned(),
            bridge: None,
            page: Page::Connection,
            locations: vec![LocationRow {
                name: "Локальный SOCKS5".to_owned(),
                endpoint: "127.0.0.1:1080".to_owned(),
                enabled: true,
            }],
            bypass_lan: true,
            bypass_russia: false,
            auto_start: false,
            system_proxy: false,
            custom_rules: "domain:myserver.lan -> Direct".to_owned(),
            tunnel_mode: true,
            selected_location: 0,
            import_text: String::new(),
            show_import: false,
        };
        defaults.load_state()
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
                if ui.selectable_label(self.page == Page::Connection, "Подключение").clicked() { self.page = Page::Connection; }
                if ui.selectable_label(self.page == Page::Locations, "Локации").clicked() { self.page = Page::Locations; }
                if ui.selectable_label(self.page == Page::Routing, "Маршрутизация").clicked() { self.page = Page::Routing; }
                if ui.selectable_label(self.page == Page::Settings, "Настройки").clicked() { self.page = Page::Settings; }
                ui.separator();
                ui.small("Rust runtime");
            });

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.add_space(22.0);
            match self.page {
                Page::Connection => self.connection_page(ui),
                Page::Locations => self.locations_page(ui),
                Page::Routing => self.routing_page(ui),
                Page::Settings => self.settings_page(ui),
            }
        });
    }
}

impl YpTunApp {
    fn state_path() -> PathBuf {
        std::env::var_os("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| std::env::var_os("HOME").map(PathBuf::from).unwrap_or_else(|| PathBuf::from(".")).join(".config"))
            .join("yptun").join("linux.json")
    }

    fn load_state(mut self) -> Self {
        if let Ok(bytes) = fs::read(Self::state_path()) {
            if let Ok(saved) = serde_json::from_slice::<SavedState>(&bytes) {
                self.endpoint = saved.endpoint; self.proxy_port = saved.proxy_port;
                if !saved.locations.is_empty() { self.locations = saved.locations; }
                self.bypass_lan = saved.bypass_lan; self.bypass_russia = saved.bypass_russia;
                self.auto_start = saved.auto_start; self.system_proxy = saved.system_proxy; self.custom_rules = saved.custom_rules;
                self.tunnel_mode = saved.tunnel_mode; self.selected_location = saved.selected_location.min(self.locations.len().saturating_sub(1));
            }
        }
        self
    }

    fn save_state(&self) {
        let state = SavedState { endpoint: self.endpoint.clone(), proxy_port: self.proxy_port.clone(), locations: self.locations.iter().map(|x| LocationRow { name: x.name.clone(), endpoint: x.endpoint.clone(), enabled: x.enabled }).collect(), bypass_lan: self.bypass_lan, bypass_russia: self.bypass_russia, auto_start: self.auto_start, system_proxy: self.system_proxy, custom_rules: self.custom_rules.clone(), tunnel_mode: self.tunnel_mode, selected_location: self.selected_location };
        let path = Self::state_path();
        if let Some(parent) = path.parent() { let _ = fs::create_dir_all(parent); }
        if let Ok(json) = serde_json::to_vec_pretty(&state) { let _ = fs::write(path, json); }
    }

    fn connection_page(&mut self, ui: &mut egui::Ui) {
        let connected = self.bridge.is_some();
        ui.horizontal(|ui| {
            ui.heading("YPtun");
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                ui.label(if connected { "Подключено" } else { "Отключено" });
            });
        });
        ui.add_space(10.0);
        ui.columns(2, |columns| {
            let left = &mut columns[0];
            left.vertical_centered(|ui| {
                ui.add_space(50.0);
                ui.label("Время подключения");
                ui.heading("00:00:00");
                ui.add_space(20.0);
                let button = egui::Button::new(if connected { "Отключить" } else { "Подключить" })
                    .min_size(egui::vec2(180.0, 80.0));
                if ui.add(button).clicked() {
                    if connected { if let Some(bridge) = self.bridge.take() { bridge.stop(); } self.status = "Отключено".into(); } else { self.start_bridge(); }
                    self.save_state();
                }
                ui.add_space(20.0);
                ui.horizontal(|ui| {
                    if ui.selectable_label(!self.tunnel_mode, "ПРОКСИ").clicked() { self.tunnel_mode = false; self.save_state(); }
                    if ui.selectable_label(self.tunnel_mode, "ТУННЕЛЬ").clicked() { self.tunnel_mode = true; self.save_state(); }
                });
            });
            let right = &mut columns[1];
            right.horizontal(|ui| { ui.label("Обновить подписки"); if ui.button("Обновить").clicked() { self.status = "Обновление…".into(); } });
            if right.button("+ Добавить конфигурацию").clicked() { self.show_import = true; }
            if self.show_import {
                right.group(|ui| {
                    ui.label("Импорт ссылки");
                    ui.text_edit_singleline(&mut self.import_text);
                    ui.horizontal(|ui| {
                        if ui.button("Импортировать").clicked() { self.import_location(); }
                        if ui.button("Отмена").clicked() { self.show_import = false; }
                    });
                });
            }
            right.add_space(10.0);
            right.label("КОНФИГУРАЦИИ");
            for (index, location) in self.locations.iter().enumerate() {
                let selected = self.selected_location == index;
                let response = right.group(|ui| {
                    ui.horizontal(|ui| { ui.radio_value(&mut self.selected_location, index, ""); ui.strong(&location.name); ui.label(if location.enabled { "Включена" } else { "Отключена" }); });
                    ui.small(&location.endpoint);
                });
                if response.response.clicked() { self.selected_location = index; self.endpoint = location.endpoint.clone(); self.save_state(); }
                if selected { right.separator(); }
            }
        });
    }

    fn locations_page(&mut self, ui: &mut egui::Ui) {
        ui.heading("Локации"); ui.add_space(12.0);
        for location in &mut self.locations {
            ui.group(|ui| { ui.horizontal(|ui| { ui.checkbox(&mut location.enabled, ""); ui.text_edit_singleline(&mut location.name); ui.label(&location.endpoint); }); });
        }
        if ui.button("Добавить локацию").clicked() { self.locations.push(LocationRow { name: "Новая локация".into(), endpoint: "127.0.0.1:1080".into(), enabled: false }); self.save_state(); }
    }

    fn routing_page(&mut self, ui: &mut egui::Ui) {
        ui.heading("Маршрутизация и rules"); ui.add_space(12.0);
        if ui.checkbox(&mut self.bypass_lan, "Bypass LAN").changed() { self.save_state(); }
        if ui.checkbox(&mut self.bypass_russia, "Bypass Russia").changed() { self.save_state(); }
        ui.separator(); ui.label("Пользовательские правила применяются до geo-правил.");
        if ui.add(egui::TextEdit::multiline(&mut self.custom_rules).hint_text("domain:myserver.lan -> Direct").desired_rows(4)).changed() { self.save_state(); }
    }

    fn settings_page(&mut self, ui: &mut egui::Ui) {
        ui.heading("Настройки"); ui.add_space(12.0);
        if ui.checkbox(&mut self.system_proxy, "Управлять системным proxy").changed() { self.save_state(); }
        if ui.checkbox(&mut self.auto_start, "Запускать вместе с системой").changed() { self.save_state(); }
        ui.separator(); ui.label("Движок: Rust desktop runtime"); ui.label("Версия клиента: 4.0.0");
    }

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

    fn import_location(&mut self) {
        let link = self.import_text.trim();
        if link.is_empty() { self.status = "Вставь ссылку конфигурации".into(); return; }
        let endpoint = link.strip_prefix("socks5://").unwrap_or(link).split(['/', '?', '#']).next().unwrap_or(link);
        if split_proxy_host_port(endpoint).is_none() {
            self.status = "Не удалось распознать endpoint".into();
            return;
        }
        self.locations.push(LocationRow { name: format!("Импортированная локация {}", self.locations.len() + 1), endpoint: endpoint.to_owned(), enabled: true });
        self.selected_location = self.locations.len() - 1;
        self.endpoint = endpoint.to_owned();
        self.import_text.clear(); self.show_import = false; self.status = "Конфигурация добавлена".into(); self.save_state();
    }
}
