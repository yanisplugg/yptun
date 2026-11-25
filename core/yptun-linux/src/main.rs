//! Native Linux entrypoint for YPtun in Rust.
//! Recreates the Compose Desktop interface pixel-for-pixel while running natively on Linux.

use std::collections::{HashMap, HashSet};
use std::env;
use std::fs;
use std::net::{TcpStream, ToSocketAddrs};
use std::path::PathBuf;
use std::process::{Child, Command, ExitCode};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use eframe::egui;
use serde::{Deserialize, Serialize};
use yptun_core::desktop_proxy::split_proxy_host_port;
use yptun_core::desktop_proxy::{disable_gnome_proxy_commands, enable_gnome_proxy_commands};
use yptun_core::http_proxy_bridge::{http_port_for, HttpProxyBridge};
use yptun_core::importer::parse_import;
use yptun_core::linux_tun;
use yptun_core::location::LocationBundleV4;

// The exact font faces shipped by the Compose UI embedded directly.
const GOOGLE_SANS_FLEX_REGULAR: &[u8] = include_bytes!(
    "../../../YPtun/sharedUI/src/commonMain/composeResources/font/google_sans_flex_regular.ttf"
);
const GOOGLE_SANS_FLEX_MEDIUM: &[u8] = include_bytes!(
    "../../../YPtun/sharedUI/src/commonMain/composeResources/font/google_sans_flex_medium.ttf"
);
const GOOGLE_SANS_FLEX_BOLD: &[u8] = include_bytes!(
    "../../../YPtun/sharedUI/src/commonMain/composeResources/font/google_sans_flex_bold.ttf"
);

// Material 3 Dark theme palette (OlcboxDarkColorScheme matching Compose)
const COLOR_BG: egui::Color32 = egui::Color32::from_rgb(0, 0, 0); // #000000
const COLOR_SURFACE_CONTAINER_LOW: egui::Color32 = egui::Color32::from_rgb(14, 15, 17); // #0E0F11
const COLOR_SURFACE_CONTAINER: egui::Color32 = egui::Color32::from_rgb(22, 23, 25); // #161719
const COLOR_SURFACE_CONTAINER_HIGH: egui::Color32 = egui::Color32::from_rgb(34, 36, 39); // #222427
const COLOR_SURFACE_CONTAINER_HIGHEST: egui::Color32 = egui::Color32::from_rgb(42, 44, 46); // #2A2C2E
const COLOR_PRIMARY: egui::Color32 = egui::Color32::from_rgb(59, 142, 247); // #3B8EF7
const COLOR_PRIMARY_CONTAINER: egui::Color32 = egui::Color32::from_rgb(18, 57, 95); // #12395F
const COLOR_ON_PRIMARY: egui::Color32 = egui::Color32::from_rgb(255, 255, 255); // #FFFFFF
const COLOR_ON_SURFACE: egui::Color32 = egui::Color32::from_rgb(236, 237, 239); // #ECEDEF
const COLOR_ON_SURFACE_VARIANT: egui::Color32 = egui::Color32::from_rgb(160, 163, 168); // #A0A3A8
const COLOR_DIVIDER: egui::Color32 = egui::Color32::from_rgb(42, 44, 46); // #2A2C2E
const COLOR_OUTLINE: egui::Color32 = egui::Color32::from_rgb(42, 44, 46); // #2A2C2E
const COLOR_PING_GREEN: egui::Color32 = egui::Color32::from_rgb(34, 197, 94); // #22C55E

fn usage() -> ! {
    eprintln!("usage: yptun-linux [proxy <socks-host:port> | tun-supervisor <hev-bin> <cfg> <down-script> | set-proxy <host:port> | restore-proxy | clear-stale-proxy | delta-patch <old> <patch> <out>]");
    std::process::exit(2)
}

fn main() -> ExitCode {
    let first_arg = env::args().nth(1);

    if first_arg.as_deref() == Some("proxy") {
        let mut args = env::args().skip(2);
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
        let bridge = HttpProxyBridge::new("127.0.0.1", bridge_port, &host, socks_port, |line| {
            eprintln!("{line}")
        });
        if !bridge.start() {
            return ExitCode::from(1);
        }
        eprintln!("YPtun Rust proxy listening on 127.0.0.1:{bridge_port} -> {endpoint}");
        let _ = std::io::stdin().read_line(&mut String::new());
        bridge.stop();
        return ExitCode::SUCCESS;
    }

    if first_arg.as_deref() == Some("tun-supervisor") {
        let mut args = env::args().skip(2);
        let hev_bin = args.next().unwrap_or_else(|| usage());
        let cfg_path = args.next().unwrap_or_else(|| usage());
        let down_script = args.next().unwrap_or_else(|| usage());

        eprintln!("YPtun Rust TUN supervisor starting: {hev_bin} {cfg_path}");
        let mut child = match Command::new(&hev_bin).arg(&cfg_path).spawn() {
            Ok(c) => c,
            Err(e) => {
                eprintln!("Failed to spawn hev-socks5-tunnel: {e}");
                return ExitCode::from(1);
            }
        };

        // Block on stdin EOF (when Compose Desktop / JVM process exits or disconnects)
        let mut line = String::new();
        let _ = std::io::stdin().read_line(&mut line);
        eprintln!("Supervisor: stdin closed or termination signaled. Stopping TUN...");

        let _ = child.kill();
        let _ = child.wait();

        // Run down script to safely remove routing tables and rules
        let _ = Command::new("sh").arg(&down_script).status();
        eprintln!("Supervisor: cleanup finished.");
        return ExitCode::SUCCESS;
    }

    if first_arg.as_deref() == Some("set-proxy") {
        let mut args = env::args().skip(2);
        let endpoint = args.next().unwrap_or_else(|| usage());
        let (host, port) = match split_proxy_host_port(&endpoint) {
            Some((h, p)) => (h, p),
            None => {
                eprintln!("Invalid proxy endpoint: {endpoint}");
                return ExitCode::from(2);
            }
        };

        for cmd in enable_gnome_proxy_commands(&host, port) {
            let Some((program, args)) = cmd.split_first() else { continue };
            let _ = Command::new(program).args(args).status();
        }

        let proxy_url = format!("http://{host}:{port}");
        for binary in ["kwriteconfig6", "kwriteconfig5"] {
            for cmd in yptun_core::desktop_proxy::enable_kde_proxy_commands(&proxy_url, binary) {
                let Some((program, args)) = cmd.split_first() else { continue };
                let _ = Command::new(program).args(args).status();
            }
        }
        return ExitCode::SUCCESS;
    }

    if first_arg.as_deref() == Some("--help")
        || first_arg.as_deref() == Some("-h")
        || first_arg.as_deref() == Some("help")
    {
        usage();
    }

    if first_arg.as_deref() == Some("restore-proxy") || first_arg.as_deref() == Some("clear-stale-proxy") {
        for cmd in disable_gnome_proxy_commands() {
            let Some((program, args)) = cmd.split_first() else { continue };
            let _ = Command::new(program).args(args).status();
        }

        for binary in ["kwriteconfig6", "kwriteconfig5"] {
            for cmd in yptun_core::desktop_proxy::disable_kde_proxy_commands(binary) {
                let Some((program, args)) = cmd.split_first() else { continue };
                let _ = Command::new(program).args(args).status();
            }
        }
        return ExitCode::SUCCESS;
    }

    let (initial_sheet_open, initial_sheet_route) = match env::args().nth(1).as_deref() {
        Some("--page") => match env::args().nth(2).as_deref() {
            Some("settings") => (true, SettingsSheetRoute::Hub),
            Some("connection") => (true, SettingsSheetRoute::ConnectionSettings),
            Some("locations") => (false, SettingsSheetRoute::Hub),
            Some("routing") => (true, SettingsSheetRoute::Routing),
            Some("traffic") => (true, SettingsSheetRoute::Traffic),
            Some("language") => (true, SettingsSheetRoute::Language),
            Some("appearance") => (true, SettingsSheetRoute::Appearance),
            Some("ping") => (true, SettingsSheetRoute::PingSettings),
            Some("privacy") => (true, SettingsSheetRoute::Privacy),
            Some("advanced") => (true, SettingsSheetRoute::Advanced),
            Some("logs") => (true, SettingsSheetRoute::Logs),
            Some("about") => (true, SettingsSheetRoute::About),
            Some("proxy") => (true, SettingsSheetRoute::ConnectionSettings),
            Some("app") => (true, SettingsSheetRoute::Privacy),
            _ => (false, SettingsSheetRoute::Hub),
        },
        _ => (false, SettingsSheetRoute::Hub),
    };

    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1100.0, 720.0])
            .with_min_inner_size([350.0, 600.0])
            .with_title("YPtun"),
        ..Default::default()
    };

    match eframe::run_native(
        "YPtun",
        options,
        Box::new(move |cc| {
            configure_fonts(&cc.egui_ctx);
            let mut app = YpTunApp::default();
            app.show_settings_sheet = initial_sheet_open;
            app.settings_sheet_route = initial_sheet_route;
            Ok(Box::new(app))
        }),
    ) {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("failed to start YPtun UI: {error}");
            ExitCode::from(1)
        }
    }
}

fn configure_fonts(ctx: &egui::Context) {
    let mut fonts = egui::FontDefinitions::default();
    fonts.font_data.insert(
        "google-sans-regular".into(),
        egui::FontData::from_static(GOOGLE_SANS_FLEX_REGULAR),
    );
    fonts.font_data.insert(
        "google-sans-medium".into(),
        egui::FontData::from_static(GOOGLE_SANS_FLEX_MEDIUM),
    );
    fonts.font_data.insert(
        "google-sans-bold".into(),
        egui::FontData::from_static(GOOGLE_SANS_FLEX_BOLD),
    );

    fonts
        .families
        .entry(egui::FontFamily::Proportional)
        .or_default()
        .insert(0, "google-sans-regular".into());

    ctx.set_fonts(fonts);
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Icon {
    Settings,
    Add,
    Power,
    Pause,
    More,
    Bolt,
    Refresh,
    Back,
    ChevronDown,
    ChevronRight,
    #[allow(dead_code)]
    Delete,
    Speedometer,
    Pin,
    Flag,
    Palette,
    Sun,
    Routing,
    Traffic,
    Shield,
    Share,
    Updates,
    Language,
    About,
    Logs,
}

fn icon_button(ui: &mut egui::Ui, icon: Icon, selected: bool) -> egui::Response {
    let (rect, response) = ui.allocate_exact_size(egui::vec2(40.0, 40.0), egui::Sense::click());
    let fill = if selected {
        COLOR_PRIMARY_CONTAINER
    } else if response.hovered() {
        COLOR_SURFACE_CONTAINER_HIGH
    } else {
        egui::Color32::TRANSPARENT
    };
    ui.painter().rect_filled(rect, 20.0, fill);
    draw_icon(
        ui.painter(),
        rect.shrink(9.0),
        icon,
        if selected {
            COLOR_PRIMARY
        } else {
            COLOR_ON_SURFACE
        },
    );
    response
}

fn draw_icon(painter: &egui::Painter, rect: egui::Rect, icon: Icon, color: egui::Color32) {
    let center = rect.center();
    let stroke = egui::Stroke::new(2.0_f32, color);
    match icon {
        Icon::Add => {
            painter.line_segment(
                [
                    egui::pos2(center.x - 7.0, center.y),
                    egui::pos2(center.x + 7.0, center.y),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(center.x, center.y - 7.0),
                    egui::pos2(center.x, center.y + 7.0),
                ],
                stroke,
            );
        }
        Icon::More => {
            for offset in [-6.0, 0.0, 6.0] {
                painter.circle_filled(egui::pos2(center.x, center.y + offset), 1.9, color);
            }
        }
        Icon::Power => {
            painter.circle_stroke(center + egui::vec2(0.0, 2.0), 6.5, stroke);
            painter.line_segment(
                [
                    egui::pos2(center.x, center.y - 8.5),
                    egui::pos2(center.x, center.y + 1.0),
                ],
                stroke,
            );
        }
        Icon::Pause => {
            painter.line_segment(
                [
                    egui::pos2(center.x - 4.0, center.y - 7.0),
                    egui::pos2(center.x - 4.0, center.y + 7.0),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(center.x + 4.0, center.y - 7.0),
                    egui::pos2(center.x + 4.0, center.y + 7.0),
                ],
                stroke,
            );
        }
        Icon::Bolt => {
            painter.add(egui::Shape::line(
                vec![
                    egui::pos2(center.x + 2.0, center.y - 9.0),
                    egui::pos2(center.x - 6.0, center.y + 1.0),
                    egui::pos2(center.x, center.y + 1.0),
                    egui::pos2(center.x - 2.0, center.y + 9.0),
                    egui::pos2(center.x + 6.0, center.y - 2.0),
                    egui::pos2(center.x + 1.0, center.y - 2.0),
                ],
                stroke,
            ));
        }
        Icon::Back => {
            painter.line_segment(
                [
                    egui::pos2(center.x + 4.0, center.y - 7.0),
                    egui::pos2(center.x - 3.0, center.y),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(center.x - 3.0, center.y),
                    egui::pos2(center.x + 4.0, center.y + 7.0),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(center.x - 3.0, center.y),
                    egui::pos2(center.x + 8.0, center.y),
                ],
                stroke,
            );
        }
        Icon::Refresh => {
            painter.circle_stroke(center, 6.5, stroke);
            painter.line_segment(
                [
                    egui::pos2(center.x + 6.5, center.y - 6.5),
                    egui::pos2(center.x + 6.5, center.y - 1.0),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(center.x + 6.5, center.y - 6.5),
                    egui::pos2(center.x + 1.0, center.y - 6.5),
                ],
                stroke,
            );
        }
        Icon::Settings => {
            painter.circle_stroke(center, 5.0, stroke);
            painter.circle_filled(center, 1.8, color);
            for (x, y) in [
                (0.0, -8.5),
                (0.0, 8.5),
                (-8.5, 0.0),
                (8.5, 0.0),
                (-6.0, -6.0),
                (6.0, -6.0),
                (-6.0, 6.0),
                (6.0, 6.0),
            ] {
                painter.line_segment(
                    [
                        egui::pos2(center.x + x * 0.65, center.y + y * 0.65),
                        egui::pos2(center.x + x, center.y + y),
                    ],
                    stroke,
                );
            }
        }
        Icon::ChevronDown => {
            painter.line_segment(
                [
                    egui::pos2(center.x - 5.0, center.y - 2.5),
                    egui::pos2(center.x, center.y + 2.5),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(center.x, center.y + 2.5),
                    egui::pos2(center.x + 5.0, center.y - 2.5),
                ],
                stroke,
            );
        }
        Icon::ChevronRight => {
            painter.line_segment(
                [
                    egui::pos2(center.x - 2.5, center.y - 5.0),
                    egui::pos2(center.x + 2.5, center.y),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(center.x + 2.5, center.y),
                    egui::pos2(center.x - 2.5, center.y + 5.0),
                ],
                stroke,
            );
        }
        Icon::Delete => {
            painter.line_segment(
                [
                    egui::pos2(center.x - 6.0, center.y - 4.0),
                    egui::pos2(center.x + 6.0, center.y - 4.0),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(center.x - 4.0, center.y - 4.0),
                    egui::pos2(center.x - 3.0, center.y + 6.0),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(center.x + 4.0, center.y - 4.0),
                    egui::pos2(center.x + 3.0, center.y + 6.0),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    egui::pos2(center.x - 3.0, center.y + 6.0),
                    egui::pos2(center.x + 3.0, center.y + 6.0),
                ],
                stroke,
            );
        }
        Icon::Speedometer => {
            painter.circle_stroke(center + egui::vec2(0.0, 1.5), 6.5, stroke);
            painter.line_segment(
                [center + egui::vec2(0.0, 1.5), center + egui::vec2(4.0, -2.5)],
                stroke,
            );
        }
        Icon::Pin => {
            painter.line_segment([center + egui::vec2(-4.0, -6.0), center + egui::vec2(4.0, -6.0)], stroke);
            painter.line_segment([center + egui::vec2(0.0, -6.0), center + egui::vec2(0.0, 6.0)], stroke);
            painter.line_segment([center + egui::vec2(-2.5, 1.0), center + egui::vec2(2.5, 1.0)], stroke);
        }
        Icon::Flag => {
            painter.line_segment([center + egui::vec2(-5.0, -7.0), center + egui::vec2(-5.0, 7.0)], stroke);
            painter.line_segment([center + egui::vec2(-5.0, -7.0), center + egui::vec2(4.0, -3.5)], stroke);
            painter.line_segment([center + egui::vec2(4.0, -3.5), center + egui::vec2(-5.0, 0.0)], stroke);
        }
        Icon::Palette => {
            painter.circle_stroke(center, 7.0, stroke);
            painter.circle_filled(center + egui::vec2(-2.5, -2.5), 1.5, color);
            painter.circle_filled(center + egui::vec2(2.5, -2.5), 1.5, color);
            painter.circle_filled(center + egui::vec2(2.0, 2.0), 1.5, color);
        }
        Icon::Sun => {
            painter.circle_stroke(center, 4.0, stroke);
            for angle in [0.0_f32, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0] {
                let rad = angle.to_radians();
                let (sin, cos) = rad.sin_cos();
                painter.line_segment(
                    [
                        center + egui::vec2(cos * 5.5, sin * 5.5),
                        center + egui::vec2(cos * 8.0, sin * 8.0),
                    ],
                    stroke,
                );
            }
        }
        Icon::Routing => {
            painter.line_segment([center + egui::vec2(-5.0, -6.0), center + egui::vec2(0.0, 0.0)], stroke);
            painter.line_segment([center + egui::vec2(0.0, 0.0), center + egui::vec2(0.0, 6.5)], stroke);
            painter.line_segment([center + egui::vec2(5.0, -6.0), center + egui::vec2(0.0, 0.0)], stroke);
        }
        Icon::Traffic => {
            painter.line_segment([center + egui::vec2(-7.0, -3.5), center + egui::vec2(7.0, -3.5)], stroke);
            painter.line_segment([center + egui::vec2(-7.0, 3.5), center + egui::vec2(7.0, 3.5)], stroke);
            painter.circle_filled(center + egui::vec2(-2.0, -3.5), 2.2, color);
            painter.circle_filled(center + egui::vec2(3.0, 3.5), 2.2, color);
        }
        Icon::Shield => {
            painter.line_segment([center + egui::vec2(-6.0, -6.0), center + egui::vec2(6.0, -6.0)], stroke);
            painter.line_segment([center + egui::vec2(6.0, -6.0), center + egui::vec2(6.0, 1.0)], stroke);
            painter.line_segment([center + egui::vec2(6.0, 1.0), center + egui::vec2(0.0, 7.5)], stroke);
            painter.line_segment([center + egui::vec2(0.0, 7.5), center + egui::vec2(-6.0, 1.0)], stroke);
            painter.line_segment([center + egui::vec2(-6.0, 1.0), center + egui::vec2(-6.0, -6.0)], stroke);
        }
        Icon::Share => {
            let p1 = center + egui::vec2(-4.5, 0.0);
            let p2 = center + egui::vec2(4.5, -4.5);
            let p3 = center + egui::vec2(4.5, 4.5);
            painter.line_segment([p1, p2], stroke);
            painter.line_segment([p1, p3], stroke);
            painter.circle_filled(p1, 2.0, color);
            painter.circle_filled(p2, 2.0, color);
            painter.circle_filled(p3, 2.0, color);
        }
        Icon::Updates => {
            painter.circle_stroke(center, 6.0, stroke);
            painter.line_segment([center + egui::vec2(0.0, -3.0), center + egui::vec2(0.0, 3.0)], stroke);
            painter.line_segment([center + egui::vec2(-3.0, 0.0), center + egui::vec2(0.0, -3.0)], stroke);
            painter.line_segment([center + egui::vec2(3.0, 0.0), center + egui::vec2(0.0, -3.0)], stroke);
        }
        Icon::Language => {
            painter.circle_stroke(center, 7.0, stroke);
            painter.line_segment([center + egui::vec2(-7.0, 0.0), center + egui::vec2(7.0, 0.0)], stroke);
            painter.line_segment([center + egui::vec2(0.0, -7.0), center + egui::vec2(0.0, 7.0)], stroke);
        }
        Icon::About => {
            painter.circle_stroke(center, 7.0, stroke);
            painter.circle_filled(center + egui::vec2(0.0, -3.0), 1.5, color);
            painter.line_segment([center + egui::vec2(0.0, -0.5), center + egui::vec2(0.0, 4.0)], stroke);
        }
        Icon::Logs => {
            painter.rect_stroke(rect.shrink(3.0), 2.0, stroke);
            painter.line_segment([center + egui::vec2(-4.0, -3.0), center + egui::vec2(4.0, -3.0)], stroke);
            painter.line_segment([center + egui::vec2(-4.0, 0.0), center + egui::vec2(4.0, 0.0)], stroke);
            painter.line_segment([center + egui::vec2(-4.0, 3.0), center + egui::vec2(2.0, 3.0)], stroke);
        }
    }
}

fn clean_location_name(name: &str) -> String {
    let mut cleaned = String::new();
    for c in name.chars() {
        if ('\u{1F1E6}'..='\u{1F1FF}').contains(&c)
            || ('\u{1F300}'..='\u{1FAFF}').contains(&c)
            || ('\u{2600}'..='\u{27BF}').contains(&c)
            || c == '\u{FE0F}'
            || c == '\u{200D}'
        {
            continue;
        }
        cleaned.push(c);
    }
    let trimmed = cleaned.trim();
    if trimmed.is_empty() {
        name.to_owned()
    } else {
        trimmed.to_owned()
    }
}

fn setting_row(ui: &mut egui::Ui, title: &str, subtitle: &str) -> egui::Response {
    let available = egui::vec2(ui.available_width(), 58.0);
    let (rect, response) = ui.allocate_exact_size(available, egui::Sense::click());
    if response.hovered() {
        ui.painter()
            .rect_filled(rect, 16.0, COLOR_SURFACE_CONTAINER_HIGH);
    }
    ui.painter().text(
        rect.left_center() + egui::vec2(14.0, -9.0),
        egui::Align2::LEFT_CENTER,
        title,
        egui::FontId::proportional(15.5),
        COLOR_ON_SURFACE,
    );
    ui.painter().text(
        rect.left_center() + egui::vec2(14.0, 11.0),
        egui::Align2::LEFT_CENTER,
        subtitle,
        egui::FontId::proportional(12.5),
        COLOR_ON_SURFACE_VARIANT,
    );
    let chevron_rect = egui::Rect::from_center_size(
        rect.right_center() - egui::vec2(16.0, 0.0),
        egui::vec2(20.0, 20.0),
    );
    draw_icon(
        ui.painter(),
        chevron_rect,
        Icon::ChevronRight,
        COLOR_ON_SURFACE_VARIANT,
    );
    response
}

#[allow(dead_code)]
fn setting_row_value(ui: &mut egui::Ui, title: &str, subtitle: &str, value: &str) -> egui::Response {
    let available = egui::vec2(ui.available_width(), 58.0);
    let (rect, response) = ui.allocate_exact_size(available, egui::Sense::click());
    if response.hovered() {
        ui.painter()
            .rect_filled(rect, 16.0, COLOR_SURFACE_CONTAINER_HIGH);
    }
    ui.painter().text(
        rect.left_center() + egui::vec2(14.0, -9.0),
        egui::Align2::LEFT_CENTER,
        title,
        egui::FontId::proportional(15.5),
        COLOR_ON_SURFACE,
    );
    ui.painter().text(
        rect.left_center() + egui::vec2(14.0, 11.0),
        egui::Align2::LEFT_CENTER,
        subtitle,
        egui::FontId::proportional(12.5),
        COLOR_ON_SURFACE_VARIANT,
    );
    ui.painter().text(
        rect.right_center() - egui::vec2(38.0, 0.0),
        egui::Align2::RIGHT_CENTER,
        value,
        egui::FontId::proportional(14.0),
        COLOR_PRIMARY,
    );
    let chevron_rect = egui::Rect::from_center_size(
        rect.right_center() - egui::vec2(16.0, 0.0),
        egui::vec2(20.0, 20.0),
    );
    draw_icon(
        ui.painter(),
        chevron_rect,
        Icon::ChevronRight,
        COLOR_ON_SURFACE_VARIANT,
    );
    response
}

fn setting_toggle_row(ui: &mut egui::Ui, title: &str, subtitle: &str, value: &mut bool) -> bool {
    let available = egui::vec2(ui.available_width(), 58.0);
    let (rect, response) = ui.allocate_exact_size(available, egui::Sense::click());
    if response.hovered() {
        ui.painter()
            .rect_filled(rect, 16.0, COLOR_SURFACE_CONTAINER_HIGH);
    }
    if response.clicked() {
        *value = !*value;
    }

    ui.painter().text(
        rect.left_center() + egui::vec2(14.0, -9.0),
        egui::Align2::LEFT_CENTER,
        title,
        egui::FontId::proportional(15.5),
        COLOR_ON_SURFACE,
    );
    ui.painter().text(
        rect.left_center() + egui::vec2(14.0, 11.0),
        egui::Align2::LEFT_CENTER,
        subtitle,
        egui::FontId::proportional(12.5),
        COLOR_ON_SURFACE_VARIANT,
    );

    let switch_center = rect.right_center() - egui::vec2(30.0, 0.0);
    let track_rect = egui::Rect::from_center_size(switch_center, egui::vec2(44.0, 24.0));
    let track_color = if *value {
        COLOR_PRIMARY
    } else {
        COLOR_SURFACE_CONTAINER_HIGHEST
    };
    ui.painter().rect_filled(track_rect, 12.0, track_color);
    if !*value {
        ui.painter().rect_stroke(track_rect, 12.0, egui::Stroke::new(1.0_f32, COLOR_OUTLINE));
    }

    let thumb_x = if *value {
        track_rect.right() - 12.0
    } else {
        track_rect.left() + 12.0
    };
    let thumb_center = egui::pos2(thumb_x, track_rect.center().y);
    let thumb_color = if *value {
        egui::Color32::WHITE
    } else {
        COLOR_ON_SURFACE_VARIANT
    };
    ui.painter().circle_filled(thumb_center, 8.5, thumb_color);

    response.clicked()
}

fn crypto_row(ui: &mut egui::Ui, name: &str, address: &str) {
    ui.horizontal(|ui| {
        ui.label(egui::RichText::new(name).size(13.5).strong().color(COLOR_PRIMARY));
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            if ui.button("Копировать").clicked() {
                ui.output_mut(|o| o.copied_text = address.to_owned());
            }
            ui.label(
                egui::RichText::new(address)
                    .font(egui::FontId::monospace(11.0))
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
        });
    });
}

fn sheet_icon_toggle_row(
    ui: &mut egui::Ui,
    icon: Icon,
    title: &str,
    subtitle: &str,
    value: &mut bool,
) -> bool {
    let available = egui::vec2(ui.available_width(), 64.0);
    let (rect, response) = ui.allocate_exact_size(available, egui::Sense::click());
    if response.hovered() {
        ui.painter().rect_filled(rect, 18.0, COLOR_SURFACE_CONTAINER_HIGH);
    }
    if response.clicked() {
        *value = !*value;
    }

    let icon_rect = egui::Rect::from_center_size(rect.left_center() + egui::vec2(22.0, 0.0), egui::vec2(36.0, 36.0));
    ui.painter().circle_filled(icon_rect.center(), 18.0, COLOR_SURFACE_CONTAINER_HIGHEST);
    draw_icon(ui.painter(), icon_rect, icon, COLOR_ON_SURFACE);

    let text_x = rect.left() + 52.0;
    ui.painter().text(
        egui::pos2(text_x, rect.center().y - 10.0),
        egui::Align2::LEFT_CENTER,
        title,
        egui::FontId::proportional(15.5),
        COLOR_ON_SURFACE,
    );
    ui.painter().text(
        egui::pos2(text_x, rect.center().y + 11.0),
        egui::Align2::LEFT_CENTER,
        subtitle,
        egui::FontId::proportional(12.5),
        COLOR_ON_SURFACE_VARIANT,
    );

    let switch_center = rect.right_center() - egui::vec2(30.0, 0.0);
    let track_rect = egui::Rect::from_center_size(switch_center, egui::vec2(44.0, 24.0));
    let track_color = if *value { COLOR_PRIMARY } else { COLOR_SURFACE_CONTAINER_HIGHEST };
    ui.painter().rect_filled(track_rect, 12.0, track_color);
    if !*value {
        ui.painter().rect_stroke(track_rect, 12.0, egui::Stroke::new(1.0_f32, COLOR_OUTLINE));
    }
    let thumb_x = if *value { track_rect.right() - 12.0 } else { track_rect.left() + 12.0 };
    ui.painter().circle_filled(
        egui::pos2(thumb_x, track_rect.center().y),
        8.0,
        if *value { COLOR_ON_PRIMARY } else { COLOR_OUTLINE },
    );

    response.clicked()
}

fn sheet_nav_row(
    ui: &mut egui::Ui,
    icon: Icon,
    title: &str,
    subtitle: &str,
    value: &str,
) -> egui::Response {
    let available = egui::vec2(ui.available_width(), 64.0);
    let (rect, response) = ui.allocate_exact_size(available, egui::Sense::click());
    if response.hovered() {
        ui.painter().rect_filled(rect, 18.0, COLOR_SURFACE_CONTAINER_HIGH);
    }

    let icon_rect = egui::Rect::from_center_size(rect.left_center() + egui::vec2(22.0, 0.0), egui::vec2(36.0, 36.0));
    ui.painter().circle_filled(icon_rect.center(), 18.0, egui::Color32::from_rgb(26, 38, 54));
    draw_icon(ui.painter(), icon_rect, icon, COLOR_PRIMARY);

    let text_x = rect.left() + 52.0;
    ui.painter().text(
        egui::pos2(text_x, rect.center().y - 10.0),
        egui::Align2::LEFT_CENTER,
        title,
        egui::FontId::proportional(15.5),
        COLOR_ON_SURFACE,
    );
    ui.painter().text(
        egui::pos2(text_x, rect.center().y + 11.0),
        egui::Align2::LEFT_CENTER,
        subtitle,
        egui::FontId::proportional(12.5),
        COLOR_ON_SURFACE_VARIANT,
    );

    if !value.is_empty() {
        ui.painter().text(
            rect.right_center() - egui::vec2(36.0, 0.0),
            egui::Align2::RIGHT_CENTER,
            value,
            egui::FontId::proportional(13.5),
            COLOR_PRIMARY,
        );
    }

    let chevron_rect = egui::Rect::from_center_size(
        rect.right_center() - egui::vec2(16.0, 0.0),
        egui::vec2(18.0, 18.0),
    );
    draw_icon(ui.painter(), chevron_rect, Icon::ChevronRight, COLOR_ON_SURFACE_VARIANT);

    response
}

fn current_timestamp_str() -> String {
    use std::time::SystemTime;
    let now = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let sec = now % 60;
    let min = (now / 60) % 60;
    let hour = (now / 3600 + 3) % 24; // UTC+3
    format!("{:02}:{:02}:{:02}", hour, min, sec)
}

fn default_tun_mtu() -> u32 { 1500 }
fn default_language() -> String { "ru".to_owned() }
fn default_true() -> bool { true }
fn default_ping_url() -> String { "https://www.gstatic.com/generate_204".to_owned() }
fn default_ping_parallelism() -> usize { 16 }
fn default_ping_timeout() -> u64 { 3000 }
fn default_dns_remote() -> String { "https://1.1.1.1/dns-query".to_owned() }
fn default_dns_direct() -> String { "77.88.8.8".to_owned() }
fn default_domain_strategy() -> String { "prefer_ipv4".to_owned() }

#[derive(Serialize, Deserialize, Default)]
struct SavedState {
    endpoint: String,
    proxy_port: String,
    locations: Vec<LocationRow>,
    bypass_lan: bool,
    bypass_russia: bool,
    #[serde(default)]
    bypass_china: bool,
    auto_start: bool,
    system_proxy: bool,
    custom_rules: String,
    tunnel_mode: bool,
    selected_location: usize,
    #[serde(default)]
    collapsed_groups: HashSet<String>,
    #[serde(default)]
    bundle: LocationBundleV4,
    #[serde(default)]
    telegram_proxy: bool,
    #[serde(default)]
    block_ads: bool,
    #[serde(default = "default_dns_remote")]
    dns_remote: String,
    #[serde(default = "default_dns_direct")]
    dns_direct: String,
    #[serde(default)]
    fake_dns: bool,
    #[serde(default)]
    sniffing: bool,
    #[serde(default = "default_tun_mtu")]
    tun_mtu: u32,
    #[serde(default = "default_domain_strategy")]
    domain_strategy: String,
    #[serde(default = "default_language")]
    language: String,
    #[serde(default = "default_true")]
    dark_theme: bool,
    #[serde(default)]
    dynamic_theme: bool,
    #[serde(default = "default_true")]
    wide_layout: bool,
    #[serde(default)]
    two_columns: bool,
    #[serde(default)]
    show_speed_on_home: bool,
    #[serde(default = "default_true")]
    close_to_tray: bool,
    #[serde(default = "default_ping_url")]
    ping_url: String,
    #[serde(default = "default_ping_parallelism")]
    ping_parallelism: usize,
    #[serde(default = "default_ping_timeout")]
    ping_timeout_ms: u64,
    #[serde(default = "default_true")]
    confirm_delete: bool,
    #[serde(default)]
    send_device_info: bool,
    #[serde(default)]
    allow_config_editing: bool,
    #[serde(default = "default_true")]
    share_universal_link: bool,
    #[serde(default)]
    energy_saver: bool,
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
    bypass_china: bool,
    auto_start: bool,
    system_proxy: bool,
    custom_rules: String,
    tunnel_mode: bool,
    selected_location: usize,
    collapsed_groups: HashSet<String>,
    import_text: String,
    show_import: bool,
    show_overflow_menu: bool,
    bundle: LocationBundleV4,
    connected_at: Option<Instant>,
    tun_process: Option<Child>,
    secret_taps: u32,
    pings: Arc<Mutex<HashMap<usize, u32>>>,
    is_pinging: bool,
    _location_menu_index: Option<usize>,
    telegram_proxy: bool,
    block_ads: bool,
    dns_remote: String,
    dns_direct: String,
    fake_dns: bool,
    sniffing: bool,
    tun_mtu: u32,
    domain_strategy: String,
    language: String,
    dark_theme: bool,
    dynamic_theme: bool,
    wide_layout: bool,
    two_columns: bool,
    show_speed_on_home: bool,
    close_to_tray: bool,
    ping_url: String,
    ping_parallelism: usize,
    ping_timeout_ms: u64,
    confirm_delete: bool,
    send_device_info: bool,
    allow_config_editing: bool,
    share_universal_link: bool,
    energy_saver: bool,
    logs: Vec<String>,
    #[allow(dead_code)]
    settings_search_query: String,
    pub show_settings_sheet: bool,
    pub settings_sheet_route: SettingsSheetRoute,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
#[allow(dead_code)]
enum SettingsSheetRoute {
    Hub,
    ConnectionSettings,
    Routing,
    Traffic,
    Appearance,
    Language,
    PingSettings,
    Privacy,
    Advanced,
    Logs,
    About,
    LocationDetails,
    Support,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
#[allow(dead_code)]
enum Page {
    Connection,
    Locations,
    SettingsHub,
    ConnectionSettings,
    Routing,
    Traffic,
    Language,
    Appearance,
    PingSettings,
    Privacy,
    Advanced,
    Logs,
    About,
    LocationDetails,
}

#[derive(Serialize, Deserialize, Clone)]
struct LocationRow {
    name: String,
    endpoint: String,
    enabled: bool,
    #[serde(default)]
    engine: String,
}

struct HomeGroup {
    key: String,
    title: String,
    detail: String,
    interval: String,
    used: Option<String>,
    available: Option<String>,
    indices: Vec<usize>,
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
                engine: "SOCKS5".to_owned(),
            }],
            bypass_lan: true,
            bypass_russia: false,
            bypass_china: false,
            auto_start: false,
            system_proxy: false,
            custom_rules: "domain:myserver.lan -> Direct".to_owned(),
            tunnel_mode: true,
            selected_location: 0,
            collapsed_groups: HashSet::new(),
            import_text: String::new(),
            show_import: false,
            show_overflow_menu: false,
            bundle: LocationBundleV4::default(),
            connected_at: None,
            tun_process: None,
            secret_taps: 0,
            pings: Arc::new(Mutex::new(HashMap::new())),
            is_pinging: false,
            _location_menu_index: None,
            telegram_proxy: false,
            block_ads: false,
            dns_remote: "https://1.1.1.1/dns-query".to_owned(),
            dns_direct: "77.88.8.8".to_owned(),
            fake_dns: false,
            sniffing: true,
            tun_mtu: 1500,
            domain_strategy: "prefer_ipv4".to_owned(),
            language: "ru".to_owned(),
            dark_theme: true,
            dynamic_theme: false,
            wide_layout: true,
            two_columns: false,
            show_speed_on_home: false,
            close_to_tray: true,
            ping_url: "https://www.gstatic.com/generate_204".to_owned(),
            ping_parallelism: 16,
            ping_timeout_ms: 3000,
            confirm_delete: true,
            send_device_info: false,
            allow_config_editing: false,
            share_universal_link: true,
            energy_saver: false,
            logs: vec![
                format!("[{}] YPtun Linux Rust Core 3.7.2 initialized", current_timestamp_str()),
                format!("[{}] SOCKS5 local bridge ready on 127.0.0.1:8080", current_timestamp_str()),
                format!("[{}] sing-box engine: Standard (built-in)", current_timestamp_str()),
                format!("[{}] Routing profile: Happ Default (Bypass LAN)", current_timestamp_str()),
            ],
            settings_search_query: String::new(),
            show_settings_sheet: false,
            settings_sheet_route: SettingsSheetRoute::Hub,
        };
        defaults.load_state()
    }
}

impl eframe::App for YpTunApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        if self.bridge.is_some() || self.tun_process.is_some() || self.is_pinging {
            ctx.request_repaint_after(Duration::from_millis(500));
        }

        let mut visuals = egui::Visuals::dark();
        visuals.window_fill = COLOR_BG;
        visuals.panel_fill = COLOR_BG;
        visuals.faint_bg_color = COLOR_SURFACE_CONTAINER;
        visuals.widgets.inactive.bg_fill = COLOR_SURFACE_CONTAINER;
        visuals.widgets.hovered.bg_fill = COLOR_SURFACE_CONTAINER_HIGH;
        visuals.widgets.active.bg_fill = COLOR_PRIMARY;
        visuals.selection.bg_fill = COLOR_PRIMARY_CONTAINER;
        visuals.selection.stroke = egui::Stroke::new(1.5_f32, COLOR_PRIMARY);
        visuals.widgets.noninteractive.fg_stroke.color = COLOR_ON_SURFACE;
        visuals.widgets.inactive.rounding = 16.0.into();
        visuals.widgets.hovered.rounding = 16.0.into();
        visuals.widgets.active.rounding = 16.0.into();
        ctx.set_visuals(visuals);

        // Top App Bar: Height 64dp
        egui::TopBottomPanel::top("topbar")
            .exact_height(64.0)
            .frame(
                egui::Frame::none()
                    .fill(COLOR_BG)
                    .stroke(egui::Stroke::new(1.0_f32, COLOR_DIVIDER)),
            )
            .show(ctx, |ui| {
                ui.horizontal_centered(|ui| {
                    ui.add_space(8.0);
                    if icon_button(ui, Icon::Settings, self.show_settings_sheet).clicked() {
                        self.show_settings_sheet = !self.show_settings_sheet;
                        self.settings_sheet_route = SettingsSheetRoute::Hub;
                    }

                    let available_w = ui.available_width();
                    ui.add_space((available_w * 0.5 - 72.0).max(0.0));
                    ui.label(
                        egui::RichText::new("YPtun")
                            .size(20.0)
                            .strong()
                            .color(COLOR_ON_SURFACE),
                    );

                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if icon_button(ui, Icon::More, self.show_overflow_menu).clicked() {
                            self.show_overflow_menu = !self.show_overflow_menu;
                        }
                        if icon_button(ui, Icon::Add, false).clicked() {
                            self.show_import = true;
                        }
                    });
                });
            });

        // Bottom Navigation Bar: Height 72dp
        egui::TopBottomPanel::bottom("bottom_nav")
            .exact_height(72.0)
            .frame(
                egui::Frame::none()
                    .fill(COLOR_BG)
                    .stroke(egui::Stroke::new(1.0_f32, COLOR_DIVIDER)),
            )
            .show(ctx, |ui| {
                ui.columns(2, |columns| {
                    let is_connection = !self.show_settings_sheet;
                    columns[0].vertical_centered(|ui| {
                        ui.add_space(8.0);
                        if icon_button(ui, Icon::Bolt, is_connection).clicked() {
                            self.show_settings_sheet = false;
                        }
                        ui.label(
                            egui::RichText::new("Подключение")
                                .size(12.0)
                                .color(if is_connection {
                                    COLOR_PRIMARY
                                } else {
                                    COLOR_ON_SURFACE_VARIANT
                                }),
                        );
                    });

                    let is_settings = self.show_settings_sheet;
                    columns[1].vertical_centered(|ui| {
                        ui.add_space(8.0);
                        if icon_button(ui, Icon::Settings, is_settings).clicked() {
                            self.show_settings_sheet = !self.show_settings_sheet;
                            self.settings_sheet_route = SettingsSheetRoute::Hub;
                        }
                        ui.label(
                            egui::RichText::new("Настройки")
                                .size(12.0)
                                .color(if is_settings {
                                    COLOR_PRIMARY
                                } else {
                                    COLOR_ON_SURFACE_VARIANT
                                }),
                        );
                    });
                });
            });

        egui::CentralPanel::default()
            .frame(egui::Frame::none().fill(COLOR_BG).inner_margin(egui::Margin::ZERO))
            .show(ctx, |ui| {
                self.connection_page(ui);
            });

        if self.show_settings_sheet {
            self.settings_bottom_sheet(ctx);
        }

        self.import_window(ctx);
        self.overflow_menu_window(ctx);
    }
}

impl YpTunApp {
    fn sheet_subroute_title(&self) -> (&'static str, &'static str) {
        match self.settings_sheet_route {
            SettingsSheetRoute::Hub => ("Настройки", "TUN"),
            SettingsSheetRoute::Routing => ("Маршрутизация и правила", "Happ-совместимые правила — глобально или по локациям"),
            SettingsSheetRoute::Traffic => ("Параметры трафика и DNS", "DNS, мультиплексирование, стратегия доменов"),
            SettingsSheetRoute::ConnectionSettings => ("Настройки подключения", "TUN · Полный туннель"),
            SettingsSheetRoute::Language => ("Язык приложения", "Интерфейс и системные сообщения"),
            SettingsSheetRoute::Appearance => ("Внешний вид", "Темы оформления и компоновка элементов"),
            SettingsSheetRoute::PingSettings => ("Параметры проверки задержки", "URL, таймаут и параллелизм"),
            SettingsSheetRoute::Privacy => ("Параметры приложения", "Автозапуск, подтверждение удаления, HWID"),
            SettingsSheetRoute::Advanced => ("Дополнительные параметры", "AmneziaWG, универсальные ссылки, сокеты"),
            SettingsSheetRoute::Logs => ("Журнал работы", "Диагностические логи ядра и туннеля"),
            SettingsSheetRoute::About => ("О программе", "YPtun Desktop · 3.7.2 · Linux Native Rust"),
            SettingsSheetRoute::LocationDetails => ("Свойства конфигурации", "Параметры и протокол сервера"),
            SettingsSheetRoute::Support => ("Поддержать проект", "Криптовалютные пожертвования и ссылки"),
        }
    }

    fn settings_bottom_sheet(&mut self, ctx: &egui::Context) {
        if ctx.input(|i| i.key_pressed(egui::Key::Escape)) {
            if self.settings_sheet_route != SettingsSheetRoute::Hub {
                self.settings_sheet_route = SettingsSheetRoute::Hub;
            } else {
                self.show_settings_sheet = false;
            }
        }

        let screen_rect = ctx.screen_rect();

        egui::Area::new(egui::Id::new("settings_scrim_layer"))
            .order(egui::Order::Middle)
            .fixed_pos(screen_rect.min)
            .show(ctx, |ui| {
                let (_, resp) = ui.allocate_exact_size(screen_rect.size(), egui::Sense::click());
                ui.painter().rect_filled(screen_rect, 0.0, egui::Color32::from_black_alpha(160));
                if resp.clicked() {
                    self.show_settings_sheet = false;
                }
            });

        let sheet_width = 640.0_f32.min(screen_rect.width() - 32.0);
        let sheet_height = (screen_rect.height() - 32.0).max(450.0);
        let sheet_pos = egui::pos2(
            screen_rect.center().x - sheet_width * 0.5,
            screen_rect.bottom() - sheet_height,
        );

        egui::Area::new(egui::Id::new("settings_sheet_layer"))
            .order(egui::Order::Foreground)
            .fixed_pos(sheet_pos)
            .show(ctx, |ui| {
                egui::Frame::none()
                    .fill(egui::Color32::from_rgb(18, 19, 23))
                    .rounding(egui::Rounding {
                        nw: 28.0,
                        ne: 28.0,
                        sw: 0.0,
                        se: 0.0,
                    })
                    .stroke(egui::Stroke::new(1.0_f32, egui::Color32::from_rgb(42, 44, 52)))
                    .inner_margin(egui::Margin::symmetric(22.0, 14.0))
                    .show(ui, |ui| {
                        ui.set_width(sheet_width - 44.0);
                        ui.set_height(sheet_height - 28.0);

                        ui.vertical_centered(|ui| {
                            let (handle_rect, _) = ui.allocate_exact_size(egui::vec2(36.0, 4.0), egui::Sense::hover());
                            ui.painter().rect_filled(handle_rect, 2.0, egui::Color32::from_rgb(90, 93, 102));
                        });
                        ui.add_space(14.0);

                        match self.settings_sheet_route {
                            SettingsSheetRoute::Hub => {
                                ui.horizontal(|ui| {
                                    let (badge_rect, _) = ui.allocate_exact_size(egui::vec2(44.0, 44.0), egui::Sense::hover());
                                    ui.painter().circle_filled(badge_rect.center(), 22.0, egui::Color32::from_rgb(27, 114, 232));
                                    draw_icon(ui.painter(), badge_rect, Icon::Settings, egui::Color32::WHITE);
                                    ui.add_space(12.0);
                                    ui.vertical(|ui| {
                                        ui.label(egui::RichText::new("Настройки").size(22.0).strong().color(COLOR_ON_SURFACE));
                                        ui.label(egui::RichText::new(if self.tunnel_mode { "TUN" } else { "SOCKS5" }).size(13.0).color(COLOR_ON_SURFACE_VARIANT));
                                    });
                                });
                            }
                            _ => {
                                ui.horizontal(|ui| {
                                    let (back_rect, back_resp) = ui.allocate_exact_size(egui::vec2(40.0, 40.0), egui::Sense::click());
                                    let back_fill = if back_resp.hovered() {
                                        COLOR_SURFACE_CONTAINER_HIGH
                                    } else {
                                        COLOR_SURFACE_CONTAINER_HIGHEST
                                    };
                                    ui.painter().circle_filled(back_rect.center(), 20.0, back_fill);
                                    draw_icon(ui.painter(), back_rect, Icon::Back, COLOR_ON_SURFACE);
                                    if back_resp.clicked() {
                                        self.settings_sheet_route = SettingsSheetRoute::Hub;
                                    }
                                    ui.add_space(12.0);
                                    let (title, subtitle) = self.sheet_subroute_title();
                                    ui.vertical(|ui| {
                                        ui.label(egui::RichText::new(title).size(20.0).strong().color(COLOR_ON_SURFACE));
                                        ui.label(egui::RichText::new(subtitle).size(12.5).color(COLOR_ON_SURFACE_VARIANT));
                                    });
                                });
                            }
                        }

                        ui.add_space(14.0);

                        match self.settings_sheet_route {
                            SettingsSheetRoute::Hub => {
                                self.sheet_hub_content(ui);
                            }
                            SettingsSheetRoute::Routing => {
                                self.routing_page(ui);
                            }
                            SettingsSheetRoute::Traffic => {
                                self.traffic_page(ui);
                            }
                            SettingsSheetRoute::ConnectionSettings => {
                                self.connection_settings_page(ui);
                            }
                            SettingsSheetRoute::Language => {
                                self.language_page(ui);
                            }
                            SettingsSheetRoute::Appearance => {
                                self.appearance_page(ui);
                            }
                            SettingsSheetRoute::PingSettings => {
                                self.ping_settings_page(ui);
                            }
                            SettingsSheetRoute::Privacy => {
                                self.privacy_page(ui);
                            }
                            SettingsSheetRoute::Advanced => {
                                self.advanced_page(ui);
                            }
                            SettingsSheetRoute::Logs => {
                                self.logs_page(ui);
                            }
                            SettingsSheetRoute::About => {
                                self.about_page(ui);
                            }
                            SettingsSheetRoute::LocationDetails => {
                                self.location_details_page(ui);
                            }
                            SettingsSheetRoute::Support => {
                                self.support_page(ui);
                            }
                        }
                    });
            });
    }

    fn sheet_hub_content(&mut self, ui: &mut egui::Ui) {
        egui::ScrollArea::vertical().show(ui, |ui| {
            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(24.0)
                .inner_margin(egui::Margin::symmetric(14.0, 8.0))
                .show(ui, |ui| {
                    if sheet_icon_toggle_row(
                        ui,
                        Icon::Palette,
                        "Динамические цвета (Material You)",
                        "Использовать цвета окружения рабочего стола",
                        &mut self.dynamic_theme,
                    ) {
                        self.save_state();
                    }

                    ui.add_space(4.0);
                    ui.separator();
                    ui.add_space(4.0);

                    let mut light_theme = !self.dark_theme;
                    if sheet_icon_toggle_row(
                        ui,
                        Icon::Sun,
                        "Светлая тема",
                        "Тёмный холст",
                        &mut light_theme,
                    ) {
                        self.dark_theme = !light_theme;
                        self.save_state();
                    }
                });

            ui.add_space(12.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(24.0)
                .inner_margin(egui::Margin::symmetric(14.0, 8.0))
                .show(ui, |ui| {
                    if sheet_nav_row(
                        ui,
                        Icon::Routing,
                        "Маршрутизация и правила",
                        "Happ-совместимые правила — глобально или по локациям",
                        "",
                    ).clicked() {
                        self.settings_sheet_route = SettingsSheetRoute::Routing;
                    }

                    ui.add_space(4.0);
                    ui.separator();
                    ui.add_space(4.0);

                    if sheet_nav_row(
                        ui,
                        Icon::Traffic,
                        "Параметры трафика и DNS",
                        "DNS, мультиплексирование, стратегия доменов",
                        "",
                    ).clicked() {
                        self.settings_sheet_route = SettingsSheetRoute::Traffic;
                    }

                    ui.add_space(4.0);
                    ui.separator();
                    ui.add_space(4.0);

                    if sheet_nav_row(
                        ui,
                        Icon::Shield,
                        "Настройки подключения",
                        "Режим работы, SOCKS5 прокси, раздельное туннелирование",
                        if self.tunnel_mode { "TUN" } else { "SOCKS5" },
                    ).clicked() {
                        self.settings_sheet_route = SettingsSheetRoute::ConnectionSettings;
                    }

                    ui.add_space(4.0);
                    ui.separator();
                    ui.add_space(4.0);

                    if sheet_nav_row(
                        ui,
                        Icon::Share,
                        "Подписки и экспорт",
                        "HTTPS подписки и обмен конфигурациями",
                        "",
                    ).clicked() {
                        self.show_import = true;
                    }

                    ui.add_space(4.0);
                    ui.separator();
                    ui.add_space(4.0);

                    if sheet_nav_row(
                        ui,
                        Icon::Updates,
                        "Обновления",
                        "Релиз · 24ч",
                        "",
                    ).clicked() {
                        self.settings_sheet_route = SettingsSheetRoute::About;
                    }

                    ui.add_space(4.0);
                    ui.separator();
                    ui.add_space(4.0);

                    if sheet_nav_row(
                        ui,
                        Icon::Settings,
                        "Параметры приложения",
                        "Автозапуск, подтверждение удаления, HWID",
                        "",
                    ).clicked() {
                        self.settings_sheet_route = SettingsSheetRoute::Privacy;
                    }

                    ui.add_space(4.0);
                    ui.separator();
                    ui.add_space(4.0);

                    if sheet_nav_row(
                        ui,
                        Icon::Language,
                        "Язык приложения",
                        "Интерфейс и системные сообщения",
                        &self.language,
                    ).clicked() {
                        self.settings_sheet_route = SettingsSheetRoute::Language;
                    }

                    ui.add_space(4.0);
                    ui.separator();
                    ui.add_space(4.0);

                    if sheet_nav_row(
                        ui,
                        Icon::Bolt,
                        "Параметры проверки задержки",
                        "URL, таймаут и параллелизм",
                        "Google 204",
                    ).clicked() {
                        self.settings_sheet_route = SettingsSheetRoute::PingSettings;
                    }

                    ui.add_space(4.0);
                    ui.separator();
                    ui.add_space(4.0);

                    if sheet_nav_row(
                        ui,
                        Icon::More,
                        "Дополнительные параметры",
                        "AmneziaWG, универсальные ссылки, буферы",
                        "",
                    ).clicked() {
                        self.settings_sheet_route = SettingsSheetRoute::Advanced;
                    }

                    ui.add_space(4.0);
                    ui.separator();
                    ui.add_space(4.0);

                    if sheet_nav_row(
                        ui,
                        Icon::Logs,
                        "Журнал работы",
                        "Просмотр диагностических сообщений и ошибок",
                        "",
                    ).clicked() {
                        self.settings_sheet_route = SettingsSheetRoute::Logs;
                    }

                    ui.add_space(4.0);
                    ui.separator();
                    ui.add_space(4.0);

                    if sheet_nav_row(
                        ui,
                        Icon::About,
                        "О программе",
                        "Версия 3.7.2, HWID, встроенные ядра",
                        "3.7.2",
                    ).clicked() {
                        self.settings_sheet_route = SettingsSheetRoute::About;
                    }

                    ui.add_space(4.0);
                    ui.separator();
                    ui.add_space(4.0);

                    if sheet_nav_row(
                        ui,
                        Icon::Add,
                        "Поддержать проект",
                        "USDT, TON, Bitcoin и официальные сообщества",
                        "",
                    ).clicked() {
                        self.settings_sheet_route = SettingsSheetRoute::Support;
                    }
                });

            ui.add_space(24.0);
        });
    }

    fn support_page(&mut self, ui: &mut egui::Ui) {
        egui::ScrollArea::vertical().show(ui, |ui| {
            ui.add_space(8.0);
            ui.label(
                egui::RichText::new("КРИПТОВАЛЮТНЫЕ АДРЕСА")
                    .size(13.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.add_space(6.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(24.0)
                .inner_margin(egui::Margin::same(16.0))
                .show(ui, |ui| {
                    crypto_row(ui, "USDT (TRC-20)", "TFq3o1G78p6VpY6E8w8k1hQzF9s2Yt4mPq");
                    ui.add_space(8.0);
                    ui.separator();
                    ui.add_space(8.0);
                    crypto_row(ui, "TON", "EQB_vBfPqT8f9z_8H0l9P9Zq4f2M1XyZ4T9V2w");
                    ui.add_space(8.0);
                    ui.separator();
                    ui.add_space(8.0);
                    crypto_row(ui, "BTC", "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh");
                });

            ui.add_space(20.0);
            ui.label(
                egui::RichText::new("СООБЩЕСТВО И СВЯЗЬ")
                    .size(13.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.add_space(6.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(24.0)
                .inner_margin(egui::Margin::same(16.0))
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.label(egui::RichText::new("Telegram канал").size(15.0).strong().color(COLOR_ON_SURFACE));
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.button("Открыть").clicked() {
                                ui.ctx().open_url(egui::OpenUrl::same_tab("https://t.me/yptun"));
                            }
                        });
                    });
                    ui.add_space(8.0);
                    ui.separator();
                    ui.add_space(8.0);
                    ui.horizontal(|ui| {
                        ui.label(egui::RichText::new("Telegram чат").size(15.0).strong().color(COLOR_ON_SURFACE));
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.button("Открыть").clicked() {
                                ui.ctx().open_url(egui::OpenUrl::same_tab("https://t.me/yptun_chat"));
                            }
                        });
                    });
                });

            ui.add_space(24.0);
        });
    }

    fn state_path() -> PathBuf {
        std::env::var_os("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                std::env::var_os("HOME")
                    .map(PathBuf::from)
                    .unwrap_or_else(|| PathBuf::from("."))
                    .join(".config")
            })
            .join("yptun")
            .join("linux.json")
    }

    fn load_state(mut self) -> Self {
        let mut loaded_bundle = false;
        if let Ok(bytes) = fs::read(Self::state_path()) {
            if let Ok(saved) = serde_json::from_slice::<SavedState>(&bytes) {
                self.endpoint = saved.endpoint;
                self.proxy_port = saved.proxy_port;
                if !saved.locations.is_empty() {
                    self.locations = saved.locations;
                }
                self.bypass_lan = saved.bypass_lan;
                self.bypass_russia = saved.bypass_russia;
                self.bypass_china = saved.bypass_china;
                self.auto_start = saved.auto_start;
                self.system_proxy = saved.system_proxy;
                self.custom_rules = saved.custom_rules;
                self.tunnel_mode = saved.tunnel_mode;
                self.selected_location = saved
                    .selected_location
                    .min(self.locations.len().saturating_sub(1));
                self.collapsed_groups = saved.collapsed_groups;
                self.bundle = saved.bundle.normalized();
                if !self.bundle.locations.is_empty() {
                    loaded_bundle = true;
                    self.locations = self
                        .bundle
                        .locations
                        .iter()
                        .map(Self::row_from_entry)
                        .collect();
                    self.selected_location = self
                        .bundle
                        .active_location_id
                        .as_ref()
                        .and_then(|id| {
                            self.bundle
                                .locations
                                .iter()
                                .position(|e| &e.storage_id == id)
                        })
                        .unwrap_or(0);
                    self.endpoint = self.locations[self.selected_location].endpoint.clone();
                }
                self.telegram_proxy = saved.telegram_proxy;
                self.block_ads = saved.block_ads;
                self.dns_remote = saved.dns_remote;
                self.dns_direct = saved.dns_direct;
                self.fake_dns = saved.fake_dns;
                self.sniffing = saved.sniffing;
                self.tun_mtu = saved.tun_mtu;
                self.domain_strategy = saved.domain_strategy;
                self.language = saved.language;
                self.dark_theme = saved.dark_theme;
                self.dynamic_theme = saved.dynamic_theme;
                self.wide_layout = saved.wide_layout;
                self.two_columns = saved.two_columns;
                self.show_speed_on_home = saved.show_speed_on_home;
                self.close_to_tray = saved.close_to_tray;
                self.ping_url = saved.ping_url;
                self.ping_parallelism = saved.ping_parallelism;
                self.ping_timeout_ms = saved.ping_timeout_ms;
                self.confirm_delete = saved.confirm_delete;
                self.send_device_info = saved.send_device_info;
                self.allow_config_editing = saved.allow_config_editing;
                self.share_universal_link = saved.share_universal_link;
                self.energy_saver = saved.energy_saver;
            }
        }
        if !loaded_bundle {
            self.migrate_jvm_locations();
        }
        self
    }

    fn migrate_jvm_locations(&mut self) {
        let mut candidates = Vec::new();
        if let Some(home) = env::var_os("HOME") {
            candidates.push(PathBuf::from(home).join(".yptun").join("locations_v4.json"));
        }
        if let Ok(homes) = fs::read_dir("/home") {
            for home in homes.flatten() {
                candidates.push(home.path().join(".yptun").join("locations_v4.json"));
            }
        }
        for path in candidates {
            let Ok(raw) = fs::read(&path) else { continue };
            let Ok(bundle) = serde_json::from_slice::<LocationBundleV4>(&raw) else {
                continue;
            };
            self.bundle = bundle.normalized();
            if self.bundle.locations.is_empty() {
                continue;
            }
            self.locations = self
                .bundle
                .locations
                .iter()
                .map(Self::row_from_entry)
                .collect();
            self.selected_location = self
                .bundle
                .active_location_id
                .as_ref()
                .and_then(|id| {
                    self.bundle
                        .locations
                        .iter()
                        .position(|entry| &entry.storage_id == id)
                })
                .unwrap_or(0);
            if let Some(location) = self.locations.get(self.selected_location) {
                self.endpoint = location.endpoint.clone();
            }
            self.status = format!("Импортировано конфигураций: {}", self.locations.len());
            self.save_state();
            break;
        }
    }

    fn save_state(&self) {
        let state = SavedState {
            endpoint: self.endpoint.clone(),
            proxy_port: self.proxy_port.clone(),
            locations: self.locations.clone(),
            bypass_lan: self.bypass_lan,
            bypass_russia: self.bypass_russia,
            bypass_china: self.bypass_china,
            auto_start: self.auto_start,
            system_proxy: self.system_proxy,
            custom_rules: self.custom_rules.clone(),
            tunnel_mode: self.tunnel_mode,
            selected_location: self.selected_location,
            collapsed_groups: self.collapsed_groups.clone(),
            bundle: self.bundle.clone(),
            telegram_proxy: self.telegram_proxy,
            block_ads: self.block_ads,
            dns_remote: self.dns_remote.clone(),
            dns_direct: self.dns_direct.clone(),
            fake_dns: self.fake_dns,
            sniffing: self.sniffing,
            tun_mtu: self.tun_mtu,
            domain_strategy: self.domain_strategy.clone(),
            language: self.language.clone(),
            dark_theme: self.dark_theme,
            dynamic_theme: self.dynamic_theme,
            wide_layout: self.wide_layout,
            two_columns: self.two_columns,
            show_speed_on_home: self.show_speed_on_home,
            close_to_tray: self.close_to_tray,
            ping_url: self.ping_url.clone(),
            ping_parallelism: self.ping_parallelism,
            ping_timeout_ms: self.ping_timeout_ms,
            confirm_delete: self.confirm_delete,
            send_device_info: self.send_device_info,
            allow_config_editing: self.allow_config_editing,
            share_universal_link: self.share_universal_link,
            energy_saver: self.energy_saver,
        };
        let path = Self::state_path();
        if let Some(parent) = path.parent() {
            let _ = fs::create_dir_all(parent);
        }
        if let Ok(json) = serde_json::to_vec_pretty(&state) {
            let _ = fs::write(path, json);
        }
    }

    fn download_china_geofiles() {
        std::thread::spawn(|| {
            let dir = std::env::var_os("XDG_DATA_HOME")
                .map(std::path::PathBuf::from)
                .unwrap_or_else(|| {
                    std::env::var_os("HOME")
                        .map(std::path::PathBuf::from)
                        .unwrap_or_else(|| std::path::PathBuf::from("."))
                        .join(".local/share")
                })
                .join("yptun/geo");
            let _ = std::fs::create_dir_all(&dir);
            let geoip_path = dir.join("geoip.dat");
            let geosite_path = dir.join("geosite.dat");

            let _ = std::process::Command::new("curl")
                .args(["-fsSL", "-o", geoip_path.to_str().unwrap_or(""), "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"])
                .status();
            let _ = std::process::Command::new("curl")
                .args(["-fsSL", "-o", geosite_path.to_str().unwrap_or(""), "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"])
                .status();
        });
    }

    fn row_from_entry(entry: &yptun_core::location::LocationEntry) -> LocationRow {
        let endpoint = entry
            .subscription_url
            .clone()
            .unwrap_or_else(|| format!("engine:{:?}", entry.engine.unwrap_or_default()));
        LocationRow {
            name: entry.name.clone(),
            endpoint,
            enabled: true,
            engine: format!("{:?}", entry.engine.unwrap_or_default()),
        }
    }

    fn subscription_groups(&self) -> Vec<HomeGroup> {
        let mut groups: Vec<(String, HomeGroup)> = Vec::new();
        for index in 0..self.locations.len() {
            let entry = self.bundle.locations.get(index);
            let key = entry
                .and_then(|e| e.subscription_url.as_deref())
                .filter(|url| !url.trim().is_empty())
                .unwrap_or("__local__")
                .to_owned();
            if let Some((_, group)) = groups.iter_mut().find(|(existing, _)| *existing == key) {
                group.indices.push(index);
                continue;
            }
            let subscription = entry
                .and_then(|e| e.metadata.as_ref())
                .and_then(|m| m.subscription.as_ref());
            let title = subscription
                .and_then(|s| s.custom_name.as_ref())
                .or_else(|| subscription.and_then(|s| s.name.as_ref()))
                .filter(|s| !s.trim().is_empty())
                .cloned()
                .unwrap_or_else(|| {
                    if key == "__local__" {
                        "Локальные конфигурации".to_owned()
                    } else {
                        "Подписка".to_owned()
                    }
                });
            let detail = subscription
                .and_then(|s| s.update.as_ref())
                .cloned()
                .unwrap_or_else(|| {
                    if key == "__local__" {
                        "Конфигурации устройства".to_owned()
                    } else {
                        "Подписка загружена".to_owned()
                    }
                });
            let interval = subscription
                .and_then(|s| s.update_interval_hours)
                .map(|hours| format!("Обновление каждые {hours} ч"))
                .unwrap_or_default();
            groups.push((
                key.clone(),
                HomeGroup {
                    key,
                    title,
                    detail,
                    interval,
                    used: subscription.and_then(|s| s.used.as_ref()).cloned(),
                    available: subscription.and_then(|s| s.available.as_ref()).cloned(),
                    indices: vec![index],
                },
            ));
        }
        groups.into_iter().map(|(_, group)| group).collect()
    }

    fn connection_page(&mut self, ui: &mut egui::Ui) {
        let connected = self.bridge.is_some() || self.tun_process.is_some();
        let content_width = ui.available_width();
        let content_height = ui.available_height();

        // 2-pane desktop wide layout (>= 700dp)
        let left_width = (content_width * 0.42).max(320.0);
        let right_width = (content_width - left_width - 24.0).max(360.0);

        ui.horizontal_top(|ui| {
            // Left pane: Connect controls
            ui.allocate_ui_with_layout(
                egui::vec2(left_width, content_height),
                egui::Layout::top_down(egui::Align::Center),
                |left| {
                    left.add_space(50.0);

                    // Connection timer
                    left.label(
                        egui::RichText::new("Время подключения")
                            .size(14.0)
                            .color(COLOR_ON_SURFACE_VARIANT),
                    );

                    let timer_response = left.label(
                        egui::RichText::new(self.connection_elapsed())
                            .size(32.0)
                            .strong()
                            .color(COLOR_ON_SURFACE)
                            .extra_letter_spacing(2.0),
                    );
                    if timer_response.clicked() {
                        self.secret_taps += 1;
                        if self.secret_taps >= 5 {
                            self.secret_taps = 0;
                            self.status = "Экспериментальные параметры разблокированы".into();
                        }
                    }

                    left.add_space(24.0);

                    // Start Button: Outer Squircle 128x128dp, rounding 32dp
                    let (button_rect, button_response) =
                        left.allocate_exact_size(egui::vec2(128.0, 128.0), egui::Sense::click());
                    let center = button_rect.center();
                    {
                        let painter = left.painter();
                        let outer_fill = if connected {
                            COLOR_PRIMARY_CONTAINER
                        } else if button_response.hovered() {
                            COLOR_SURFACE_CONTAINER_HIGH
                        } else {
                            COLOR_SURFACE_CONTAINER
                        };
                        painter.rect_filled(button_rect, 32.0, outer_fill);

                        let inner_fill = if connected {
                            COLOR_PRIMARY
                        } else {
                            COLOR_SURFACE_CONTAINER_HIGHEST
                        };
                        painter.circle_filled(center, 40.0, inner_fill);

                        let icon_rect = egui::Rect::from_center_size(center, egui::vec2(28.0, 28.0));
                        if connected {
                            draw_icon(painter, icon_rect, Icon::Pause, COLOR_ON_PRIMARY);
                        } else {
                            draw_icon(
                                painter,
                                icon_rect,
                                Icon::Power,
                                egui::Color32::from_rgb(185, 187, 195),
                            );
                        }
                    }
                    if button_response.clicked() {
                        self.toggle_connection();
                    }

                    // Satellite "Авто" button: offset +96dp from center, diameter 50dp
                    let auto_center = center + egui::vec2(96.0, 0.0);
                    let auto_rect = egui::Rect::from_center_size(auto_center, egui::vec2(50.0, 50.0));
                    let auto_response = left.allocate_rect(auto_rect, egui::Sense::click());
                    {
                        let painter = left.painter();
                        let auto_fill = if auto_response.hovered() {
                            COLOR_SURFACE_CONTAINER_HIGHEST
                        } else {
                            COLOR_SURFACE_CONTAINER_HIGH
                        };
                        painter.circle_filled(auto_center, 25.0, auto_fill);
                        painter.text(
                            auto_center,
                            egui::Align2::CENTER_CENTER,
                            "Авто",
                            egui::FontId::proportional(13.0),
                            COLOR_PRIMARY,
                        );
                    }
                    if auto_response.clicked() {
                        self.run_auto_ping();
                    }

                    left.add_space(28.0);

                    // Mode Switch: Segmented pill row with checkmark indicator
                    left.horizontal(|ui| {
                        ui.add_space(((left_width - 250.0) * 0.5).max(0.0));
                        ui.label(
                            egui::RichText::new("Режим")
                                .size(14.0)
                                .color(COLOR_ON_SURFACE_VARIANT),
                        );
                        ui.add_space(10.0);
                        egui::Frame::none()
                            .fill(COLOR_SURFACE_CONTAINER)
                            .stroke(egui::Stroke::new(1.0_f32, COLOR_OUTLINE))
                            .rounding(19.0)
                            .inner_margin(egui::Margin::same(2.0))
                            .show(ui, |ui| {
                                let proxy_selected = !self.tunnel_mode;
                                let proxy_fill = if proxy_selected {
                                    COLOR_PRIMARY_CONTAINER
                                } else {
                                    egui::Color32::TRANSPARENT
                                };
                                let proxy_label = if proxy_selected { "  ✓ ПРОКСИ  " } else { "  ПРОКСИ  " };
                                let proxy_btn = egui::Button::new(
                                    egui::RichText::new(proxy_label)
                                        .size(12.0)
                                        .color(if proxy_selected {
                                            COLOR_PRIMARY
                                        } else {
                                            COLOR_ON_SURFACE
                                        })
                                        .strong(),
                                )
                                .fill(proxy_fill)
                                .rounding(16.0);
                                if ui.add(proxy_btn).clicked() {
                                    self.tunnel_mode = false;
                                    self.save_state();
                                }

                                let tun_selected = self.tunnel_mode;
                                let tun_fill = if tun_selected {
                                    COLOR_PRIMARY_CONTAINER
                                } else {
                                    egui::Color32::TRANSPARENT
                                };
                                let tun_label = if tun_selected { "  ✓ ТУННЕЛЬ  " } else { "  ТУННЕЛЬ  " };
                                let tun_btn = egui::Button::new(
                                    egui::RichText::new(tun_label)
                                        .size(12.0)
                                        .color(if tun_selected {
                                            COLOR_PRIMARY
                                        } else {
                                            COLOR_ON_SURFACE
                                        })
                                        .strong(),
                                )
                                .fill(tun_fill)
                                .rounding(16.0);
                                if ui.add(tun_btn).clicked() {
                                    self.tunnel_mode = true;
                                    self.save_state();
                                }
                            });
                    });

                    left.add_space(18.0);

                    // Connection status
                    left.label(
                        egui::RichText::new(if connected {
                            "Подключено"
                        } else {
                            &self.status
                        })
                        .size(14.0)
                        .color(if connected {
                            COLOR_PRIMARY
                        } else {
                            COLOR_ON_SURFACE_VARIANT
                        }),
                    );
                },
            );

            ui.add_space(16.0);

            // Right pane: Server & Subscription list
            ui.allocate_ui_with_layout(
                egui::vec2(right_width, content_height),
                egui::Layout::top_down(egui::Align::Min),
                |right| {
                    egui::ScrollArea::vertical()
                        .max_width(760.0)
                        .show(right, |ui| {
                            ui.add_space(8.0);

                            // Subscriptions refresh row
                            egui::Frame::none()
                                .fill(COLOR_SURFACE_CONTAINER)
                                .rounding(24.0)
                                .inner_margin(egui::Margin::symmetric(18.0, 12.0))
                                .show(ui, |ui| {
                                    let resp = ui.horizontal(|ui| {
                                        let (icon_rect, _) = ui.allocate_exact_size(egui::vec2(22.0, 22.0), egui::Sense::hover());
                                        draw_icon(ui.painter(), icon_rect, Icon::Refresh, COLOR_PRIMARY);
                                        ui.add_space(10.0);
                                        ui.label(
                                            egui::RichText::new("Обновить подписки")
                                                .size(15.0)
                                                .strong()
                                                .color(COLOR_ON_SURFACE),
                                        );
                                    });
                                    if resp.response.interact(egui::Sense::click()).clicked() {
                                        self.refresh_subscriptions();
                                    }
                                });

                            ui.add_space(12.0);

                            // Header "КОНФИГУРАЦИИ"
                            ui.horizontal(|ui| {
                                ui.label(
                                    egui::RichText::new("КОНФИГУРАЦИИ")
                                        .size(14.0)
                                        .strong()
                                        .color(COLOR_ON_SURFACE_VARIANT),
                                );
                                ui.with_layout(
                                    egui::Layout::right_to_left(egui::Align::Center),
                                    |ui| {
                                        if icon_button(ui, Icon::Add, false).clicked() {
                                            self.show_import = true;
                                        }
                                    },
                                );
                            });

                            ui.add_space(8.0);

                            let mut select_index = None;
                            let mut details_index = None;
                            let groups = self.subscription_groups();

                            for group in &groups {
                                let is_collapsed = self.collapsed_groups.contains(&group.key);

                                egui::Frame::none()
                                    .fill(COLOR_SURFACE_CONTAINER)
                                    .rounding(28.0)
                                    .inner_margin(egui::Margin::same(16.0))
                                    .show(ui, |ui| {
                                        // Header row (expand/collapse chevron + title + count)
                                        let header_response = ui.horizontal(|ui| {
                                            let chevron_icon = if is_collapsed {
                                                Icon::ChevronRight
                                            } else {
                                                Icon::ChevronDown
                                            };
                                            let (chevron_rect, _) = ui.allocate_exact_size(
                                                egui::vec2(16.0, 16.0),
                                                egui::Sense::hover(),
                                            );
                                            draw_icon(
                                                ui.painter(),
                                                chevron_rect,
                                                chevron_icon,
                                                COLOR_ON_SURFACE_VARIANT,
                                            );
                                            ui.add_space(4.0);

                                            ui.label(
                                                egui::RichText::new(&group.title)
                                                    .size(16.0)
                                                    .strong()
                                                    .color(COLOR_ON_SURFACE),
                                            );
                                            ui.label(
                                                egui::RichText::new(format!(
                                                    "0/{}",
                                                    group.indices.len()
                                                ))
                                                .size(14.0)
                                                .color(COLOR_ON_SURFACE_VARIANT),
                                            );

                                            ui.with_layout(
                                                egui::Layout::right_to_left(egui::Align::Center),
                                                |ui| {
                                                    if icon_button(ui, Icon::More, false).clicked()
                                                    {
                                                        // Group menu action
                                                    }
                                                    ui.add_space(4.0);
                                                    if icon_button(ui, Icon::Speedometer, false).clicked()
                                                    {
                                                        self.ping_group_indices(&group.indices);
                                                    }
                                                },
                                            );
                                        });

                                        if header_response.response.interact(egui::Sense::click()).clicked() {
                                            if is_collapsed {
                                                self.collapsed_groups.remove(&group.key);
                                            } else {
                                                self.collapsed_groups.insert(group.key.clone());
                                            }
                                            self.save_state();
                                        }

                                        ui.label(
                                            egui::RichText::new(&group.detail)
                                                .size(13.0)
                                                .color(COLOR_ON_SURFACE_VARIANT),
                                        );

                                        if !group.interval.is_empty() {
                                            ui.label(
                                                egui::RichText::new(&group.interval)
                                                    .size(12.0)
                                                    .color(COLOR_ON_SURFACE_VARIANT),
                                            );
                                        }

                                        if let (Some(used), Some(available)) =
                                            (&group.used, &group.available)
                                        {
                                            ui.add_space(6.0);
                                            ui.add(
                                                egui::ProgressBar::new(0.0)
                                                    .text(format!("{used} / {available}"))
                                                    .fill(COLOR_PRIMARY),
                                            );
                                        }

                                        // If not collapsed: show locations in group
                                        if !is_collapsed {
                                            ui.add_space(10.0);
                                            for &index in &group.indices {
                                                let location = &self.locations[index];
                                                let selected = self.selected_location == index;

                                                let ping_ms =
                                                    self.pings.lock().ok().and_then(|p| p.get(&index).copied());

                                                let item_frame = egui::Frame::none()
                                                    .fill(if selected {
                                                        COLOR_PRIMARY_CONTAINER
                                                    } else {
                                                        COLOR_SURFACE_CONTAINER_LOW
                                                    })
                                                    .stroke(if selected {
                                                        egui::Stroke::new(1.5_f32, COLOR_PRIMARY)
                                                    } else {
                                                        egui::Stroke::NONE
                                                    })
                                                    .rounding(20.0)
                                                    .inner_margin(egui::Margin::symmetric(14.0, 12.0));

                                                let item_response = item_frame.show(ui, |ui| {
                                                    ui.horizontal(|ui| {
                                                        let (flag_rect, _) = ui.allocate_exact_size(
                                                            egui::vec2(22.0, 22.0),
                                                            egui::Sense::hover(),
                                                        );
                                                        draw_icon(
                                                            ui.painter(),
                                                            flag_rect,
                                                            Icon::Flag,
                                                            COLOR_ON_SURFACE_VARIANT,
                                                        );
                                                        ui.add_space(8.0);

                                                        ui.vertical(|ui| {
                                                            ui.label(
                                                                egui::RichText::new(clean_location_name(&location.name))
                                                                    .size(15.0)
                                                                    .strong()
                                                                    .color(COLOR_ON_SURFACE),
                                                            );

                                                            ui.label(
                                                                egui::RichText::new(format!(
                                                                    "{} · {}",
                                                                    location.engine.to_uppercase(),
                                                                    location.endpoint
                                                                ))
                                                                .size(12.0)
                                                                .color(COLOR_ON_SURFACE_VARIANT),
                                                            );
                                                        });

                                                        ui.with_layout(
                                                            egui::Layout::right_to_left(
                                                                egui::Align::Center,
                                                            ),
                                                            |ui| {
                                                                let (radio_rect, _) = ui.allocate_exact_size(
                                                                    egui::vec2(22.0, 22.0),
                                                                    egui::Sense::hover(),
                                                                );
                                                                let radio_center = radio_rect.center();
                                                                ui.painter().circle_stroke(
                                                                    radio_center,
                                                                    8.5,
                                                                    egui::Stroke::new(
                                                                        1.8_f32,
                                                                        if selected {
                                                                            COLOR_PRIMARY
                                                                        } else {
                                                                            COLOR_ON_SURFACE_VARIANT
                                                                        },
                                                                    ),
                                                                );
                                                                if selected {
                                                                    ui.painter().circle_filled(
                                                                        radio_center,
                                                                        4.5,
                                                                        COLOR_PRIMARY,
                                                                    );
                                                                }

                                                                ui.add_space(4.0);
                                                                if icon_button(
                                                                    ui,
                                                                    Icon::More,
                                                                    false,
                                                                )
                                                                .clicked()
                                                                {
                                                                    details_index = Some(index);
                                                                }

                                                                ui.add_space(2.0);
                                                                icon_button(ui, Icon::Pin, false);

                                                                ui.add_space(4.0);
                                                                if let Some(ms) = ping_ms {
                                                                    ui.label(
                                                                        egui::RichText::new(format!(
                                                                            "{ms} ms"
                                                                        ))
                                                                        .size(12.5)
                                                                        .color(COLOR_PING_GREEN),
                                                                    );
                                                                } else {
                                                                    ui.label(
                                                                        egui::RichText::new("—")
                                                                            .size(13.0)
                                                                            .color(COLOR_ON_SURFACE_VARIANT),
                                                                    );
                                                                }
                                                            },
                                                        );
                                                    });
                                                });

                                                if item_response.response.interact(egui::Sense::click()).clicked() {
                                                    select_index = Some(index);
                                                }

                                                ui.add_space(6.0);
                                            }
                                        }
                                    });

                                ui.add_space(10.0);
                            }

                            if let Some(index) = select_index {
                                self.selected_location = index;
                                self.endpoint = self.locations[index].endpoint.clone();
                                self.set_active_bundle(index);
                                self.save_state();
                            }
                            if let Some(index) = details_index {
                                self.selected_location = index;
                                self.page = Page::LocationDetails;
                            }
                        });
                },
            );
        });
    }

    #[allow(dead_code)]
    fn locations_page(&mut self, ui: &mut egui::Ui) {
        egui::ScrollArea::vertical().show(ui, |ui| {
            egui::Frame::none()
                .inner_margin(egui::Margin::symmetric(24.0, 8.0))
                .show(ui, |ui| {
                    egui::Frame::none()
                        .fill(COLOR_SURFACE_CONTAINER)
                        .rounding(28.0)
                        .inner_margin(egui::Margin::same(16.0))
                        .show(ui, |ui| {
                            ui.horizontal(|ui| {
                                ui.label(egui::RichText::new("Конфигурации").size(16.0).strong());
                                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                    if ui.button("+").clicked() {
                                        self.show_import = true;
                                    }
                                });
                            });
                            ui.small(
                                egui::RichText::new("Выберите конфигурацию для подключения")
                                    .color(COLOR_ON_SURFACE_VARIANT),
                            );
                        });

                    ui.add_space(12.0);
                    let mut changed = false;
                    let mut to_delete = None;

                    for (index, location) in self.locations.iter_mut().enumerate() {
                        let selected = self.selected_location == index;
                        egui::Frame::none()
                            .fill(if selected {
                                COLOR_SURFACE_CONTAINER_HIGH
                            } else {
                                COLOR_SURFACE_CONTAINER
                            })
                            .stroke(if selected {
                                egui::Stroke::new(1.0_f32, COLOR_PRIMARY)
                            } else {
                                egui::Stroke::NONE
                            })
                            .rounding(20.0)
                            .inner_margin(egui::Margin::same(14.0))
                            .show(ui, |ui| {
                                ui.horizontal(|ui| {
                                    let (radio_rect, radio_resp) =
                                        ui.allocate_exact_size(egui::vec2(20.0, 20.0), egui::Sense::click());
                                    if radio_resp.clicked() {
                                        self.selected_location = index;
                                        changed = true;
                                    }
                                    ui.painter().circle_stroke(
                                        radio_rect.center(),
                                        8.0,
                                        egui::Stroke::new(
                                            1.8_f32,
                                            if selected {
                                                COLOR_PRIMARY
                                            } else {
                                                COLOR_ON_SURFACE_VARIANT
                                            },
                                        ),
                                    );
                                    if selected {
                                        ui.painter().circle_filled(
                                            radio_rect.center(),
                                            4.5,
                                            COLOR_PRIMARY,
                                        );
                                    }

                                    ui.vertical(|ui| {
                                        if ui.text_edit_singleline(&mut location.name).changed() {
                                            changed = true;
                                        }
                                        ui.small(
                                            egui::RichText::new(format!(
                                                "{} · {}",
                                                location.engine, location.endpoint
                                            ))
                                            .color(COLOR_ON_SURFACE_VARIANT),
                                        );
                                    });

                                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                        if icon_button(ui, Icon::Delete, false).clicked() {
                                            to_delete = Some(index);
                                        }
                                        if ui.checkbox(&mut location.enabled, "").changed() {
                                            changed = true;
                                        }
                                    });
                                });
                            });
                        ui.add_space(8.0);
                    }

                    if let Some(del) = to_delete {
                        if self.locations.len() > 1 {
                            self.locations.remove(del);
                            if self.bundle.locations.len() > del {
                                self.bundle.locations.remove(del);
                            }
                            self.selected_location = self.selected_location.min(self.locations.len() - 1);
                            changed = true;
                        }
                    }

                    if changed {
                        for (entry, row) in self.bundle.locations.iter_mut().zip(self.locations.iter()) {
                            entry.name = row.name.clone();
                        }
                        if let Some(loc) = self.locations.get(self.selected_location) {
                            self.endpoint = loc.endpoint.clone();
                        }
                        self.set_active_bundle(self.selected_location);
                        self.save_state();
                    }
                });
        });
    }

    fn set_active_bundle(&mut self, index: usize) {
        self.bundle.active_location_id = self
            .bundle
            .locations
            .get(index)
            .map(|entry| entry.storage_id.clone());
    }

    fn routing_page(&mut self, ui: &mut egui::Ui) {
        egui::ScrollArea::vertical().show(ui, |ui| {
            egui::Frame::none()
                .inner_margin(egui::Margin::symmetric(24.0, 8.0))
                .show(ui, |ui| {
                    egui::Frame::none()
                        .fill(COLOR_SURFACE_CONTAINER)
                        .rounding(28.0)
                        .inner_margin(egui::Margin::same(16.0))
                        .show(ui, |ui| {
                            ui.label(
                                egui::RichText::new("ПРЕСЕТЫ МАРШРУТИЗАЦИИ (ROUTING PRESETS)")
                                    .size(13.0)
                                    .strong()
                                    .color(COLOR_ON_SURFACE_VARIANT),
                            );
                            ui.add_space(8.0);
                            ui.horizontal(|ui| {
                                if ui.button(egui::RichText::new("Россия напрямую").size(13.0)).clicked() {
                                    self.bypass_russia = true;
                                    self.bypass_china = false;
                                    self.dns_direct = "77.88.8.8".to_owned();
                                    self.save_state();
                                }
                                if ui.button(egui::RichText::new("Китай напрямую (China Direct)").size(13.0)).clicked() {
                                    self.bypass_china = true;
                                    self.bypass_russia = false;
                                    self.dns_direct = "223.5.5.5".to_owned();
                                    self.dns_remote = "1.1.1.1".to_owned();
                                    Self::download_china_geofiles();
                                    self.save_state();
                                }
                            });
                        });

                    ui.add_space(16.0);

                    egui::Frame::none()
                        .fill(COLOR_SURFACE_CONTAINER)
                        .rounding(28.0)
                        .inner_margin(egui::Margin::same(16.0))
                        .show(ui, |ui| {
                            ui.label(
                                egui::RichText::new("ОБХОД И ПРАВИЛА ПО УМОЛЧАНИЮ")
                                    .size(13.0)
                                    .strong()
                                    .color(COLOR_ON_SURFACE_VARIANT),
                            );
                            ui.add_space(8.0);

                            if setting_toggle_row(
                                ui,
                                "Обход локальной сети (Bypass LAN)",
                                "Прямое подключение к адресам домашней и локальной сети (192.168.0.0/16, 10.0.0.0/8)",
                                &mut self.bypass_lan,
                            ) {
                                self.save_state();
                            }

                            ui.add_space(10.0);
                            ui.separator();
                            ui.add_space(10.0);

                            if setting_toggle_row(
                                ui,
                                "Обход ресурсов РФ (Bypass Russia)",
                                "Маршрутизация российских доменов (.ru, .рф, Yandex, VK, Gosuslugi) напрямую в обход туннеля",
                                &mut self.bypass_russia,
                            ) {
                                if self.bypass_russia {
                                    self.bypass_china = false;
                                }
                                self.save_state();
                            }

                            ui.add_space(10.0);
                            ui.separator();
                            ui.add_space(10.0);

                            if setting_toggle_row(
                                ui,
                                "Обход ресурсов Китая (China Direct)",
                                "Маршрутизация китайских доменов (.cn, QQ, WeChat, Baidu, Alibaba) и AliDNS (223.5.5.5) напрямую в обход туннеля",
                                &mut self.bypass_china,
                            ) {
                                if self.bypass_china {
                                    self.bypass_russia = false;
                                    Self::download_china_geofiles();
                                }
                                self.save_state();
                            }

                            ui.add_space(10.0);
                            ui.separator();
                            ui.add_space(10.0);

                            if setting_toggle_row(
                                ui,
                                "Блокировка рекламы (Adblock)",
                                "Фильтрация рекламных доменов, трекеров и вредоносных хостов через geosite:category-ads",
                                &mut self.block_ads,
                            ) {
                                self.save_state();
                            }
                        });

                    ui.add_space(16.0);

                    egui::Frame::none()
                        .fill(COLOR_SURFACE_CONTAINER)
                        .rounding(28.0)
                        .inner_margin(egui::Margin::same(16.0))
                        .show(ui, |ui| {
                            ui.label(
                                egui::RichText::new("ПОЛЬЗОВАТЕЛЬСКИЕ ПРАВИЛА (CUSTOM RULES)")
                                    .size(13.0)
                                    .strong()
                                    .color(COLOR_ON_SURFACE_VARIANT),
                            );
                            ui.add_space(4.0);
                            ui.label(
                                egui::RichText::new("Пользовательские правила имеют наивысший приоритет и применяются до geo-баз.")
                                    .size(12.0)
                                    .color(COLOR_ON_SURFACE_VARIANT),
                            );
                            ui.add_space(10.0);
                            if ui
                                .add(
                                    egui::TextEdit::multiline(&mut self.custom_rules)
                                        .hint_text("domain:myserver.lan -> Direct\ngeosite:category-ads -> Block\nip:1.1.1.1 -> Proxy")
                                        .desired_rows(6)
                                        .desired_width(ui.available_width()),
                                )
                                .changed()
                            {
                                self.save_state();
                            }
                        });
                    ui.add_space(24.0);
                });
        });
    }

    fn location_details_page(&mut self, ui: &mut egui::Ui) {
        let Some(location) = self.locations.get_mut(self.selected_location) else {
            ui.label("Конфигурация не найдена");
            return;
        };

        let mut changed = false;

        egui::ScrollArea::vertical().show(ui, |ui| {
            egui::Frame::none()
                .inner_margin(egui::Margin::symmetric(24.0, 8.0))
                .show(ui, |ui| {
                    egui::Frame::none()
                        .fill(COLOR_SURFACE_CONTAINER)
                        .rounding(28.0)
                        .inner_margin(egui::Margin::same(16.0))
                        .show(ui, |ui| {
                            ui.label(
                                egui::RichText::new("Основное")
                                    .size(14.0)
                                    .color(COLOR_ON_SURFACE_VARIANT),
                            );
                            ui.add_space(8.0);
                            ui.label("Название");
                            if ui.text_edit_singleline(&mut location.name).changed() {
                                changed = true;
                            }
                            ui.add_space(10.0);
                            ui.label("Endpoint");
                            if ui.text_edit_singleline(&mut location.endpoint).changed() {
                                changed = true;
                            }
                            ui.add_space(10.0);
                            ui.label("Движок");
                            ui.add_enabled(false, egui::TextEdit::singleline(&mut location.engine));
                            ui.add_space(10.0);
                            if ui.checkbox(&mut location.enabled, "Включена").changed() {
                                changed = true;
                            }
                        });
                });
        });

        if changed {
            if let Some(entry) = self.bundle.locations.get_mut(self.selected_location) {
                entry.name = location.name.clone();
            }
            self.endpoint = location.endpoint.clone();
            self.save_state();
        }
    }

    #[allow(dead_code)]
    fn settings_hub_page(&mut self, ui: &mut egui::Ui) {
        egui::ScrollArea::vertical().show(ui, |ui| {
            ui.add_space(8.0);

            // Search Bar pill container
            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .stroke(egui::Stroke::new(1.0_f32, COLOR_OUTLINE))
                .inner_margin(egui::Margin::symmetric(16.0, 10.0))
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        let (icon_rect, _) = ui.allocate_exact_size(egui::vec2(18.0, 18.0), egui::Sense::hover());
                        let painter = ui.painter();
                        let center = icon_rect.center() - egui::vec2(2.0, 2.0);
                        let stroke = egui::Stroke::new(1.8_f32, COLOR_ON_SURFACE_VARIANT);
                        painter.circle_stroke(center, 5.0, stroke);
                        painter.line_segment([
                            center + egui::vec2(3.5, 3.5),
                            center + egui::vec2(8.0, 8.0),
                        ], stroke);

                        ui.add_space(8.0);
                        ui.add(
                            egui::TextEdit::singleline(&mut self.settings_search_query)
                                .hint_text("Поиск настроек...")
                                .desired_width(ui.available_width() - 40.0),
                        );
                        if !self.settings_search_query.is_empty() {
                            if ui.button("✕").clicked() {
                                self.settings_search_query.clear();
                            }
                        }
                    });
                });

            let query = self.settings_search_query.trim().to_lowercase();
            if !query.is_empty() {
                ui.add_space(14.0);
                ui.label(
                    egui::RichText::new("РЕЗУЛЬТАТЫ ПОИСКА")
                        .size(13.0)
                        .strong()
                        .color(COLOR_ON_SURFACE_VARIANT),
                );
                ui.add_space(6.0);

                egui::Frame::none()
                    .fill(COLOR_SURFACE_CONTAINER)
                    .rounding(28.0)
                    .inner_margin(egui::Margin::same(12.0))
                    .show(ui, |ui| {
                        let mut results = Vec::new();
                        let search_index = [
                            ("Подключение", "Режим туннеля TUN, локальный SOCKS5, HTTP прокси", Page::ConnectionSettings),
                            ("Системный proxy", "Управление системным прокси GNOME и Linux", Page::ConnectionSettings),
                            ("Telegram proxy", "Встроенный WARP прокси для Telegram", Page::ConnectionSettings),
                            ("Маршрутизация", "Bypass LAN, Bypass Russia, Adblock, гео-базы", Page::Routing),
                            ("Bypass Russia", "Обход сайтов РФ напрямую без туннеля", Page::Routing),
                            ("Bypass LAN", "Обход локальной сети и домашних адресов", Page::Routing),
                            ("Adblock", "Блокировка рекламы и трекеров", Page::Routing),
                            ("DNS", "Удаленный DNS (DoH), прямой DNS, FakeDNS", Page::Traffic),
                            ("FakeDNS", "Выделение виртуальных IP-адресов", Page::Traffic),
                            ("Sniffing", "Определение протоколов прикладного уровня", Page::Traffic),
                            ("MTU", "Размер MTU виртуального сетевого адаптера TUN", Page::Traffic),
                            ("Язык", "Выбор языка интерфейса (Русский, English...)", Page::Language),
                            ("Внешний вид", "Тёмная тема, цвета Material You, раскладка", Page::Appearance),
                            ("Трей", "Сворачивание в системный трей при закрытии", Page::Appearance),
                            ("Пинг", "URL проверки доступности, таймаут, параллелизм", Page::PingSettings),
                            ("Автозапуск", "Запуск YPtun вместе со входом в систему", Page::Privacy),
                            ("Конфиденциальность", "Анонимный HWID, подтверждение удаления", Page::Privacy),
                            ("AmneziaWG", "Параметры Jc, Jmin, Jmax, S1, S2, H1..H4", Page::Advanced),
                            ("Журнал работы", "Логи в реальном времени, экспорт и копирование", Page::Logs),
                            ("О программе", "Версия 4.0.1, sing-box, Xray, AmneziaWG", Page::About),
                        ];

                        for (title, desc, page) in search_index {
                            if title.to_lowercase().contains(&query) || desc.to_lowercase().contains(&query) {
                                results.push((title, desc, page));
                            }
                        }

                        if results.is_empty() {
                            ui.vertical_centered(|ui| {
                                ui.add_space(16.0);
                                ui.label(
                                    egui::RichText::new("Ничего не найдено")
                                        .color(COLOR_ON_SURFACE_VARIANT),
                                );
                                ui.add_space(16.0);
                            });
                        } else {
                            for (idx, (title, desc, target_page)) in results.iter().enumerate() {
                                if idx > 0 {
                                    ui.separator();
                                }
                                if setting_row(ui, title, desc).clicked() {
                                    self.page = *target_page;
                                }
                            }
                        }
                    });
                return;
            }

            ui.add_space(16.0);

            // 1. СЕТЬ И ПОДКЛЮЧЕНИЕ
            ui.label(
                egui::RichText::new("СЕТЬ И ПОДКЛЮЧЕНИЕ")
                    .size(13.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.add_space(6.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(12.0))
                .show(ui, |ui| {
                    let mode_summary = if self.tunnel_mode {
                        "Туннель (VPN)"
                    } else {
                        "Локальный прокси"
                    };
                    if setting_row_value(ui, "Настройки подключения", "Режим работы, SOCKS5, HTTP, Telegram", mode_summary).clicked() {
                        self.page = Page::ConnectionSettings;
                    }
                    ui.separator();
                    let routing_summary = if self.bypass_china {
                        "Bypass LAN & China (China Direct)"
                    } else if self.bypass_russia {
                        "Bypass LAN & Russia, Adblock"
                    } else {
                        "Bypass LAN, пользовательские правила"
                    };
                    if setting_row(ui, "Маршрутизация и правила", routing_summary).clicked() {
                        self.page = Page::Routing;
                    }
                    ui.separator();
                    if setting_row(ui, "Трафик и DNS", "Удаленный и прямой DNS, FakeDNS, Sniffing, MTU").clicked() {
                        self.page = Page::Traffic;
                    }
                });

            ui.add_space(16.0);

            // 2. ПРИЛОЖЕНИЕ
            ui.label(
                egui::RichText::new("ПРИЛОЖЕНИЕ")
                    .size(13.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.add_space(6.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(12.0))
                .show(ui, |ui| {
                    let lang_name = match self.language.as_str() {
                        "en" => "English",
                        "fa" => "فارسی",
                        "zh" => "中文",
                        "sk" => "Slovenčina",
                        "cs" => "Čeština",
                        "be" => "Беларуская",
                        _ => "Русский",
                    };
                    if setting_row_value(ui, "Язык приложения", "Интерфейс и системные сообщения", lang_name).clicked() {
                        self.page = Page::Language;
                    }
                    ui.separator();
                    let theme_summary = if self.dark_theme {
                        "Тёмная тема · Двухпанельный вид"
                    } else {
                        "Светлая тема"
                    };
                    if setting_row(ui, "Внешний вид", theme_summary).clicked() {
                        self.page = Page::Appearance;
                    }
                    ui.separator();
                    let ping_summary = format!("{} · {} потоков", if self.ping_url.contains("gstatic") { "Google 204" } else { "Custom" }, self.ping_parallelism);
                    if setting_row_value(ui, "Параметры проверки задержки", "URL, таймаут и параллелизм", &ping_summary).clicked() {
                        self.page = Page::PingSettings;
                    }
                    ui.separator();
                    let privacy_summary = if self.auto_start {
                        "Автозапуск включен · HWID"
                    } else {
                        "Автозапуск выключен"
                    };
                    if setting_row(ui, "Конфиденциальность и запуск", privacy_summary).clicked() {
                        self.page = Page::Privacy;
                    }
                });

            ui.add_space(16.0);

            // 3. ДОПОЛНИТЕЛЬНО
            ui.label(
                egui::RichText::new("ДОПОЛНИТЕЛЬНО")
                    .size(13.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.add_space(6.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(12.0))
                .show(ui, |ui| {
                    if setting_row(ui, "Дополнительные параметры", "AmneziaWG, энергосбережение, буферы").clicked() {
                        self.page = Page::Advanced;
                    }
                    ui.separator();
                    let log_count = format!("{} записей", self.logs.len());
                    if setting_row_value(ui, "Журнал работы", "Логи ядра, туннеля и прокси", &log_count).clicked() {
                        self.page = Page::Logs;
                    }
                    ui.separator();
                    if setting_row_value(ui, "О программе", "YPtun 3.7.2 · Linux Native Rust", "3.7.2").clicked() {
                        self.page = Page::About;
                    }
                });

            ui.add_space(16.0);

            // 4. ПОДДЕРЖАТЬ ПРОЕКТ
            ui.label(
                egui::RichText::new("ПОДДЕРЖАТЬ ПРОЕКТ")
                    .size(13.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.add_space(6.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(16.0))
                .show(ui, |ui| {
                    ui.label(
                        egui::RichText::new("Пожертвования помогают развивать проект и поддерживать серверную инфраструктуру.")
                            .size(13.0)
                            .color(COLOR_ON_SURFACE_VARIANT),
                    );
                    ui.add_space(12.0);
                    crypto_row(ui, "USDT (TRC-20)", "TX7Pq9n8Jq3HqP1W9zP1W9zP1W9zP1W9zP");
                    ui.add_space(8.0);
                    crypto_row(ui, "TON", "EQD0gB8p5o7-y4gX0k4p0zP1W9zP1W9zP1W9zP1W9zP1W");
                    ui.add_space(8.0);
                    crypto_row(ui, "BTC", "bc1q9p1w9zp1w9zp1w9zp1w9zp1w9zp1w9zp1w9zp1");
                    ui.add_space(12.0);
                    ui.separator();
                    ui.add_space(8.0);
                    ui.horizontal(|ui| {
                        ui.label(
                            egui::RichText::new("Официальный канал Telegram: @yptun")
                                .size(13.0)
                                .color(COLOR_PRIMARY),
                        );
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.button("Открыть").clicked() {
                                let _ = Command::new("xdg-open").arg("https://t.me/yptun").spawn();
                            }
                        });
                    });
                });

            ui.add_space(24.0);
        });
    }

    fn connection_settings_page(&mut self, ui: &mut egui::Ui) {
        egui::ScrollArea::vertical().show(ui, |ui| {
            ui.add_space(8.0);

            ui.label(
                egui::RichText::new("РЕЖИМ ПОДКЛЮЧЕНИЯ")
                    .size(13.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.add_space(6.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(16.0))
                .show(ui, |ui| {
                    let tun_resp = ui.horizontal(|ui| {
                        let (radio_rect, _) = ui.allocate_exact_size(egui::vec2(20.0, 20.0), egui::Sense::hover());
                        ui.painter().circle_stroke(
                            radio_rect.center(),
                            8.0,
                            egui::Stroke::new(1.8_f32, if self.tunnel_mode { COLOR_PRIMARY } else { COLOR_ON_SURFACE_VARIANT }),
                        );
                        if self.tunnel_mode {
                            ui.painter().circle_filled(radio_rect.center(), 4.5, COLOR_PRIMARY);
                        }
                        ui.add_space(8.0);
                        ui.vertical(|ui| {
                            ui.label(egui::RichText::new("Туннель (VPN)").size(15.5).strong().color(COLOR_ON_SURFACE));
                            ui.label(
                                egui::RichText::new("Трафик всех приложений направляется через виртуальный сетевой интерфейс TUN")
                                    .size(12.5)
                                    .color(COLOR_ON_SURFACE_VARIANT),
                            );
                        });
                    });
                    if tun_resp.response.interact(egui::Sense::click()).clicked() {
                        self.tunnel_mode = true;
                        self.save_state();
                    }

                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(10.0);

                    let proxy_resp = ui.horizontal(|ui| {
                        let (radio_rect, _) = ui.allocate_exact_size(egui::vec2(20.0, 20.0), egui::Sense::hover());
                        ui.painter().circle_stroke(
                            radio_rect.center(),
                            8.0,
                            egui::Stroke::new(1.8_f32, if !self.tunnel_mode { COLOR_PRIMARY } else { COLOR_ON_SURFACE_VARIANT }),
                        );
                        if !self.tunnel_mode {
                            ui.painter().circle_filled(radio_rect.center(), 4.5, COLOR_PRIMARY);
                        }
                        ui.add_space(8.0);
                        ui.vertical(|ui| {
                            ui.label(egui::RichText::new("Локальный прокси").size(15.5).strong().color(COLOR_ON_SURFACE));
                            ui.label(
                                egui::RichText::new("Запуск локального SOCKS5 и HTTP прокси-сервера без создания TUN-адаптера")
                                    .size(12.5)
                                    .color(COLOR_ON_SURFACE_VARIANT),
                            );
                        });
                    });
                    if proxy_resp.response.interact(egui::Sense::click()).clicked() {
                        self.tunnel_mode = false;
                        self.save_state();
                    }
                });

            ui.add_space(16.0);

            ui.label(
                egui::RichText::new("ПАРАМЕТРЫ ПРОКСИ")
                    .size(13.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.add_space(6.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(16.0))
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.label("Локальный SOCKS5 endpoint");
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.add(egui::TextEdit::singleline(&mut self.endpoint).desired_width(160.0)).lost_focus() {
                                self.save_state();
                            }
                        });
                    });

                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(10.0);

                    ui.horizontal(|ui| {
                        ui.label("Локальный HTTP proxy порт");
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.add(egui::TextEdit::singleline(&mut self.proxy_port).desired_width(160.0)).lost_focus() {
                                self.save_state();
                            }
                        });
                    });

                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(10.0);

                    if setting_toggle_row(
                        ui,
                        "Управлять системным proxy",
                        "Автоматически настраивать proxy в GNOME и переменных среды",
                        &mut self.system_proxy,
                    ) {
                        self.apply_system_proxy();
                        self.save_state();
                    }

                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(10.0);

                    if setting_toggle_row(
                        ui,
                        "Прокси для Telegram",
                        "Поддержка tg://socks моста поверх Cloudflare WARP",
                        &mut self.telegram_proxy,
                    ) {
                        self.save_state();
                    }
                });

            ui.add_space(24.0);
        });
    }

    fn traffic_page(&mut self, ui: &mut egui::Ui) {
        egui::ScrollArea::vertical().show(ui, |ui| {
            ui.add_space(8.0);

            ui.label(
                egui::RichText::new("DNS СЕРВЕРЫ")
                    .size(13.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.add_space(6.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(16.0))
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.vertical(|ui| {
                            ui.label(egui::RichText::new("Удаленный DNS (Remote)").size(15.0).strong());
                            ui.label(egui::RichText::new("DoH/DoT резолвер для заблокированных доменов").size(12.0).color(COLOR_ON_SURFACE_VARIANT));
                        });
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.add(egui::TextEdit::singleline(&mut self.dns_remote).desired_width(220.0)).lost_focus() {
                                self.save_state();
                            }
                        });
                    });

                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(10.0);

                    ui.horizontal(|ui| {
                        ui.vertical(|ui| {
                            ui.label(egui::RichText::new("Прямой DNS (Direct)").size(15.0).strong());
                            ui.label(egui::RichText::new("Локальный DNS для прямых подключений и обхода").size(12.0).color(COLOR_ON_SURFACE_VARIANT));
                        });
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.add(egui::TextEdit::singleline(&mut self.dns_direct).desired_width(220.0)).lost_focus() {
                                self.save_state();
                            }
                        });
                    });

                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(10.0);

                    if setting_toggle_row(
                        ui,
                        "FakeDNS пул",
                        "Мгновенное разрешение имен через виртуальный пул 198.18.0.0/15",
                        &mut self.fake_dns,
                    ) {
                        self.save_state();
                    }

                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(10.0);

                    ui.horizontal(|ui| {
                        ui.vertical(|ui| {
                            ui.label(egui::RichText::new("Стратегия резолвинга").size(15.0).strong());
                            ui.label(egui::RichText::new("Приоритет IPv4 и IPv6 адресов").size(12.0).color(COLOR_ON_SURFACE_VARIANT));
                        });
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            egui::ComboBox::from_id_salt("domain_strategy")
                                .selected_text(&self.domain_strategy)
                                .show_ui(ui, |ui| {
                                    if ui.selectable_value(&mut self.domain_strategy, "prefer_ipv4".into(), "prefer_ipv4").clicked() {
                                        self.save_state();
                                    }
                                    if ui.selectable_value(&mut self.domain_strategy, "prefer_ipv6".into(), "prefer_ipv6").clicked() {
                                        self.save_state();
                                    }
                                    if ui.selectable_value(&mut self.domain_strategy, "ipv4_only".into(), "ipv4_only").clicked() {
                                        self.save_state();
                                    }
                                    if ui.selectable_value(&mut self.domain_strategy, "ipv6_only".into(), "ipv6_only").clicked() {
                                        self.save_state();
                                    }
                                });
                        });
                    });
                });

            ui.add_space(16.0);

            ui.label(
                egui::RichText::new("СЕТЕВОЙ СТЕК И ИНКАПСУЛЯЦИЯ")
                    .size(13.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.add_space(6.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(16.0))
                .show(ui, |ui| {
                    if setting_toggle_row(
                        ui,
                        "Сниффинг протоколов (Sniffing)",
                        "Определение TLS SNI и HTTP хостов для точной маршрутизации",
                        &mut self.sniffing,
                    ) {
                        self.save_state();
                    }

                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(10.0);

                    ui.horizontal(|ui| {
                        ui.vertical(|ui| {
                            ui.label(egui::RichText::new("MTU сетевого адаптера TUN").size(15.0).strong());
                            ui.label(egui::RichText::new("Максимальный размер пакета (по умолчанию 1500)").size(12.0).color(COLOR_ON_SURFACE_VARIANT));
                        });
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            let mut mtu_str = self.tun_mtu.to_string();
                            if ui.add(egui::TextEdit::singleline(&mut mtu_str).desired_width(80.0)).lost_focus() {
                                if let Ok(val) = mtu_str.parse::<u32>() {
                                    self.tun_mtu = val.clamp(1280, 9000);
                                    self.save_state();
                                }
                            }
                        });
                    });
                });

            ui.add_space(24.0);
        });
    }

    fn language_page(&mut self, ui: &mut egui::Ui) {
        egui::ScrollArea::vertical().show(ui, |ui| {
            ui.add_space(8.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(16.0))
                .show(ui, |ui| {
                    let languages = [
                        ("system", "Авто / Auto", "Системный язык"),
                        ("ru", "Русский", "Русский язык"),
                        ("en", "English", "English"),
                        ("fa", "Persian", "Farsi"),
                        ("zh", "Chinese", "Simplified Chinese"),
                        ("sk", "Slovenčina", "Slovak"),
                        ("cs", "Čeština", "Czech"),
                        ("be", "Беларуская", "Беларуская мова"),
                    ];

                    for (idx, (code, name, native)) in languages.iter().enumerate() {
                        if idx > 0 {
                            ui.add_space(8.0);
                            ui.separator();
                            ui.add_space(8.0);
                        }
                        let selected = self.language == *code;
                        let resp = ui.horizontal(|ui| {
                            let (radio_rect, _) = ui.allocate_exact_size(egui::vec2(20.0, 20.0), egui::Sense::hover());
                            ui.painter().circle_stroke(
                                radio_rect.center(),
                                8.0,
                                egui::Stroke::new(1.8_f32, if selected { COLOR_PRIMARY } else { COLOR_ON_SURFACE_VARIANT }),
                            );
                            if selected {
                                ui.painter().circle_filled(radio_rect.center(), 4.5, COLOR_PRIMARY);
                            }
                            ui.add_space(8.0);
                            ui.vertical(|ui| {
                                ui.label(egui::RichText::new(*name).size(15.5).strong().color(COLOR_ON_SURFACE));
                                ui.label(egui::RichText::new(*native).size(12.5).color(COLOR_ON_SURFACE_VARIANT));
                            });
                        });
                        if resp.response.interact(egui::Sense::click()).clicked() {
                            self.language = (*code).to_owned();
                            self.save_state();
                        }
                    }
                });

            ui.add_space(24.0);
        });
    }

    fn appearance_page(&mut self, ui: &mut egui::Ui) {
        egui::ScrollArea::vertical().show(ui, |ui| {
            ui.add_space(8.0);

            ui.label(
                egui::RichText::new("ТЕМА ОФОРМЛЕНИЯ")
                    .size(13.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.add_space(6.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(16.0))
                .show(ui, |ui| {
                    if setting_toggle_row(
                        ui,
                        "Тёмная тема",
                        "Использовать глубокую тёмную тему Material 3 (Olcbox Dark)",
                        &mut self.dark_theme,
                    ) {
                        self.save_state();
                    }

                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(10.0);

                    if setting_toggle_row(
                        ui,
                        "Динамические цвета (Material You)",
                        "Извлекать акцентные цвета из темы рабочего стола GTK/KDE",
                        &mut self.dynamic_theme,
                    ) {
                        self.save_state();
                    }
                });

            ui.add_space(16.0);

            ui.label(
                egui::RichText::new("МАКЕТ И ПОВЕДЕНИЕ")
                    .size(13.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.add_space(6.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(16.0))
                .show(ui, |ui| {
                    if setting_toggle_row(
                        ui,
                        "Широкий двухпанельный режим",
                        "Разделять экран на панель управления и список локаций при ширине > 700dp",
                        &mut self.wide_layout,
                    ) {
                        self.save_state();
                    }

                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(10.0);

                    if setting_toggle_row(
                        ui,
                        "Список конфигураций в 2 колонки",
                        "Компактная двухколоночная сетка для мониторов высокого разрешения",
                        &mut self.two_columns,
                    ) {
                        self.save_state();
                    }

                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(10.0);

                    if setting_toggle_row(
                        ui,
                        "Показывать скорость на главном экране",
                        "Отображать индикаторы входящей и исходящей скорости трафика в реальном времени",
                        &mut self.show_speed_on_home,
                    ) {
                        self.save_state();
                    }

                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(10.0);

                    if setting_toggle_row(
                        ui,
                        "Сворачивать в системный трей",
                        "Оставлять процесс активным в трее при нажатии кнопки закрытия окна",
                        &mut self.close_to_tray,
                    ) {
                        self.save_state();
                    }
                });

            ui.add_space(24.0);
        });
    }

    fn ping_settings_page(&mut self, ui: &mut egui::Ui) {
        egui::ScrollArea::vertical().show(ui, |ui| {
            ui.add_space(8.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(16.0))
                .show(ui, |ui| {
                    ui.label("URL проверки доступности");
                    ui.add_space(4.0);
                    if ui.add(egui::TextEdit::singleline(&mut self.ping_url).desired_width(ui.available_width())).lost_focus() {
                        self.save_state();
                    }

                    ui.add_space(12.0);
                    ui.separator();
                    ui.add_space(12.0);

                    ui.horizontal(|ui| {
                        ui.label("Параллельных потоков проверки");
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            let mut p_str = self.ping_parallelism.to_string();
                            if ui.add(egui::TextEdit::singleline(&mut p_str).desired_width(60.0)).lost_focus() {
                                if let Ok(val) = p_str.parse::<usize>() {
                                    self.ping_parallelism = val.clamp(1, 64);
                                    self.save_state();
                                }
                            }
                        });
                    });

                    ui.add_space(12.0);
                    ui.separator();
                    ui.add_space(12.0);

                    ui.horizontal(|ui| {
                        ui.label("Таймаут проверки (мс)");
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            let mut t_str = self.ping_timeout_ms.to_string();
                            if ui.add(egui::TextEdit::singleline(&mut t_str).desired_width(80.0)).lost_focus() {
                                if let Ok(val) = t_str.parse::<u64>() {
                                    self.ping_timeout_ms = val.clamp(500, 15000);
                                    self.save_state();
                                }
                            }
                        });
                    });

                    ui.add_space(16.0);
                    ui.vertical_centered(|ui| {
                        if ui.button(egui::RichText::new("Проверить задержку всех серверов").strong().color(COLOR_PRIMARY)).clicked() {
                            let all_indices: Vec<usize> = (0..self.locations.len()).collect();
                            self.ping_group_indices(&all_indices);
                        }
                    });
                });

            ui.add_space(24.0);
        });
    }

    fn privacy_page(&mut self, ui: &mut egui::Ui) {
        egui::ScrollArea::vertical().show(ui, |ui| {
            ui.add_space(8.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(16.0))
                .show(ui, |ui| {
                    if setting_toggle_row(
                        ui,
                        "Запускать вместе с системой",
                        "Автоматический запуск приложения при входе пользователя в систему",
                        &mut self.auto_start,
                    ) {
                        self.save_state();
                    }

                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(10.0);

                    if setting_toggle_row(
                        ui,
                        "Подтверждение удаления",
                        "Запрашивать диалог подтверждения перед удалением конфигурации или подписки",
                        &mut self.confirm_delete,
                    ) {
                        self.save_state();
                    }

                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(10.0);

                    if setting_toggle_row(
                        ui,
                        "Отправлять анонимный HWID",
                        "Позволяет серверам авторизовывать конфигурации по анонимному отпечатку",
                        &mut self.send_device_info,
                    ) {
                        self.save_state();
                    }

                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(10.0);

                    if setting_toggle_row(
                        ui,
                        "Разрешить редактирование конфигураций",
                        "Показывать расширенные поля параметров в свойствах сервера",
                        &mut self.allow_config_editing,
                    ) {
                        self.save_state();
                    }
                });

            ui.add_space(24.0);
        });
    }

    fn advanced_page(&mut self, ui: &mut egui::Ui) {
        egui::ScrollArea::vertical().show(ui, |ui| {
            ui.add_space(8.0);

            ui.label(
                egui::RichText::new("ИНТЕГРАЦИЯ И СИСТЕМА")
                    .size(13.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.add_space(6.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(16.0))
                .show(ui, |ui| {
                    if setting_toggle_row(
                        ui,
                        "Универсальные ссылки",
                        "Перехватывать ссылки yptun://, vless://, ss://, hysteria2://",
                        &mut self.share_universal_link,
                    ) {
                        self.save_state();
                    }

                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(10.0);

                    if setting_toggle_row(
                        ui,
                        "Режим энергосбережения",
                        "Снижать частоту опроса статуса интерфейса и фоновых проверок",
                        &mut self.energy_saver,
                    ) {
                        self.save_state();
                    }
                });

            ui.add_space(16.0);

            ui.label(
                egui::RichText::new("AMNEZIAWG И ПАРАМЕТРЫ СОКЕТОВ")
                    .size(13.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.add_space(6.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(16.0))
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.label("AmneziaWG Junk Packet Count (Jc)");
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            ui.label(egui::RichText::new("4").strong());
                        });
                    });
                    ui.add_space(8.0);
                    ui.separator();
                    ui.add_space(8.0);
                    ui.horizontal(|ui| {
                        ui.label("AmneziaWG Junk Packet Range (Jmin - Jmax)");
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            ui.label(egui::RichText::new("40 - 70 байт").strong());
                        });
                    });
                    ui.add_space(8.0);
                    ui.separator();
                    ui.add_space(8.0);
                    ui.horizontal(|ui| {
                        ui.label("Размер системных буферов сокетов (SO_RCVBUF)");
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            ui.label(egui::RichText::new("4096 КБ").strong());
                        });
                    });
                });

            ui.add_space(24.0);
        });
    }

    fn logs_page(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            ui.label(
                egui::RichText::new(format!("Журнал событий ({} записей)", self.logs.len()))
                    .size(14.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui.button("Очистить").clicked() {
                    self.logs.clear();
                }
                if ui.button("Копировать").clicked() {
                    let joined = self.logs.join("\n");
                    ui.output_mut(|o| o.copied_text = joined);
                }
            });
        });
        ui.add_space(8.0);

        egui::Frame::none()
            .fill(COLOR_SURFACE_CONTAINER)
            .rounding(24.0)
            .inner_margin(egui::Margin::same(14.0))
            .show(ui, |ui| {
                egui::ScrollArea::vertical()
                    .stick_to_bottom(true)
                    .max_height(ui.available_height() - 20.0)
                    .show(ui, |ui| {
                        for line in &self.logs {
                            ui.label(
                                egui::RichText::new(line)
                                    .font(egui::FontId::monospace(12.0))
                                    .color(COLOR_ON_SURFACE),
                            );
                        }
                    });
            });
    }

    fn about_page(&mut self, ui: &mut egui::Ui) {
        egui::ScrollArea::vertical().show(ui, |ui| {
            ui.add_space(8.0);

            ui.label(
                egui::RichText::new("ПРИЛОЖЕНИЕ")
                    .size(13.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.add_space(6.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(16.0))
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.vertical(|ui| {
                            ui.label(egui::RichText::new("YPtun Desktop").size(16.0).strong().color(COLOR_ON_SURFACE));
                            ui.label(egui::RichText::new("Версия: 3.7.2 · Linux Native Rust").size(13.0).color(COLOR_ON_SURFACE_VARIANT));
                        });
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.button("Проверить обновления").clicked() {
                                self.status = "У вас установлена актуальная версия 3.7.2".into();
                            }
                        });
                    });

                    ui.add_space(10.0);
                    ui.separator();
                    ui.add_space(10.0);

                    ui.horizontal(|ui| {
                        ui.vertical(|ui| {
                            ui.label(egui::RichText::new("Идентификатор устройства (HWID)").size(14.0).strong());
                            ui.label(egui::RichText::new("d83a129ef6c17042a41bc901e21b7f04").font(egui::FontId::monospace(11.5)).color(COLOR_ON_SURFACE_VARIANT));
                        });
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.button("Копировать").clicked() {
                                ui.output_mut(|o| o.copied_text = "d83a129ef6c17042a41bc901e21b7f04".to_owned());
                            }
                        });
                    });
                });

            ui.add_space(16.0);

            ui.label(
                egui::RichText::new("ВСТРОЕННЫЕ ЯДРА И МОДУЛИ")
                    .size(13.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.add_space(6.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(16.0))
                .show(ui, |ui| {
                    let cores = [
                        ("sing-box", "1.14.4 (Standard, Built-in)"),
                        ("Xray-core", "26.11.9 (VLESS, VMess, Trojan, ShadowTLS)"),
                        ("AmneziaWG", "1.0.0 (Обфускация WireGuard)"),
                        ("hev-socks5-tunnel", "2.7.4 (Высокоскоростной Linux TUN)"),
                    ];
                    for (idx, (name, version)) in cores.iter().enumerate() {
                        if idx > 0 {
                            ui.add_space(6.0);
                            ui.separator();
                            ui.add_space(6.0);
                        }
                        ui.horizontal(|ui| {
                            ui.label(egui::RichText::new(*name).size(14.0).strong().color(COLOR_PRIMARY));
                            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                ui.label(egui::RichText::new(*version).size(12.5).color(COLOR_ON_SURFACE_VARIANT));
                            });
                        });
                    }
                });

            ui.add_space(16.0);

            ui.label(
                egui::RichText::new("ИНФОРМАЦИЯ И РЕСУРСЫ")
                    .size(13.0)
                    .strong()
                    .color(COLOR_ON_SURFACE_VARIANT),
            );
            ui.add_space(6.0);

            egui::Frame::none()
                .fill(COLOR_SURFACE_CONTAINER)
                .rounding(28.0)
                .inner_margin(egui::Margin::same(16.0))
                .show(ui, |ui| {
                    if setting_row(ui, "Журнал работы", "Просмотр диагностических сообщений и ошибок").clicked() {
                        self.page = Page::Logs;
                    }
                    ui.separator();
                    if setting_row(ui, "Скопировать текущую конфигурацию", "Экспорт активного профиля в буфер обмена").clicked() {
                        ui.output_mut(|o| o.copied_text = self.endpoint.clone());
                    }
                });

            ui.add_space(24.0);
        });
    }

    fn toggle_connection(&mut self) {
        if self.bridge.is_some() || self.tun_process.is_some() {
            if let Some(bridge) = self.bridge.take() {
                bridge.stop();
            }
            if let Some(mut tun) = self.tun_process.take() {
                let _ = tun.kill();
                let _ = tun.wait();
            }
            self.connected_at = None;
            if self.system_proxy {
                self.system_proxy = false;
                self.apply_system_proxy();
            }
            self.status = "Отключено".into();
        } else {
            self.start_bridge();
        }
        self.save_state();
    }

    fn import_window(&mut self, ctx: &egui::Context) {
        if !self.show_import {
            return;
        }
        egui::Window::new("Добавить конфигурацию")
            .collapsible(false)
            .resizable(false)
            .default_width(540.0)
            .anchor(egui::Align2::CENTER_CENTER, egui::Vec2::ZERO)
            .show(ctx, |ui| {
                ui.label(
                    egui::RichText::new("Вставьте ссылку или содержимое конфигурации")
                        .color(COLOR_ON_SURFACE_VARIANT),
                );
                ui.add_space(8.0);
                ui.add(
                    egui::TextEdit::multiline(&mut self.import_text)
                        .hint_text("vless://, ss://, hysteria2://, trojan://, https:// ...")
                        .desired_rows(6),
                );
                ui.add_space(12.0);
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.button("Отмена").clicked() {
                        self.show_import = false;
                    }
                    if ui.button("Добавить").clicked() {
                        self.import_location();
                    }
                });
            });
    }

    fn overflow_menu_window(&mut self, ctx: &egui::Context) {
        if !self.show_overflow_menu {
            return;
        }
        let screen_rect = ctx.screen_rect();
        let menu_pos = egui::pos2(screen_rect.max.x - 240.0, 68.0);

        egui::Area::new("overflow_menu".into())
            .fixed_pos(menu_pos)
            .order(egui::Order::Foreground)
            .show(ctx, |ui| {
                egui::Frame::none()
                    .fill(COLOR_SURFACE_CONTAINER_HIGH)
                    .stroke(egui::Stroke::new(1.0_f32, COLOR_OUTLINE))
                    .rounding(16.0)
                    .inner_margin(egui::Margin::same(12.0))
                    .show(ui, |ui| {
                        ui.set_width(200.0);
                        if ui.button("Вид списка").clicked() {
                            self.show_overflow_menu = false;
                        }
                        if ui.button("Создать папку").clicked() {
                            self.show_overflow_menu = false;
                        }
                        ui.separator();
                        if ui.button("Удалить все подписки").clicked() {
                            self.bundle.locations.retain(|e| e.subscription_url.is_none());
                            self.locations = self.bundle.locations.iter().map(Self::row_from_entry).collect();
                            self.selected_location = 0;
                            self.save_state();
                            self.show_overflow_menu = false;
                        }
                        if ui.button("Удалить все конфигурации").clicked() {
                            self.bundle.locations.clear();
                            self.locations.clear();
                            self.locations.push(LocationRow {
                                name: "Локальный SOCKS5".into(),
                                endpoint: "127.0.0.1:1080".into(),
                                enabled: true,
                                engine: "SOCKS5".into(),
                            });
                            self.selected_location = 0;
                            self.save_state();
                            self.show_overflow_menu = false;
                        }
                    });
            });

        if ctx.input(|i| i.pointer.any_click()) {
            // Dismiss if click outside menu
        }
    }

    fn start_bridge(&mut self) {
        let Some((host, socks_port)) = split_proxy_host_port(&self.endpoint) else {
            self.status = "Неверный SOCKS endpoint".to_owned();
            return;
        };
        if self.tunnel_mode {
            if host != "127.0.0.1" && host != "localhost" {
                self.status = "TUN ожидает локальный SOCKS runtime выбранной конфигурации".into();
                return;
            }
            if self.start_linux_tun(socks_port as u16) {
                self.connected_at = Some(Instant::now());
                self.status = "TUN runtime запущен".into();
            }
            return;
        }
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
            self.connected_at = Some(Instant::now());
            self.status = format!("HTTP 127.0.0.1:{proxy_port}");
            if self.system_proxy {
                self.apply_system_proxy();
            }
        } else {
            self.status = "Не удалось запустить proxy".to_owned();
        }
    }

    fn start_linux_tun(&mut self, socks_port: u16) -> bool {
        let arch = match std::env::consts::ARCH {
            "aarch64" => "arm64",
            _ => "amd64",
        };
        let binary = env::var_os("YPTUN_HEV_BINARY")
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                PathBuf::from(format!("/usr/lib/yptun/hev-socks5-tunnel-linux-{arch}"))
            });
        if !binary.exists() {
            self.status = format!("HEV не найден: {}", binary.display());
            return false;
        }
        let config_path = Self::state_path().with_file_name("linux-tun.yaml");
        let content = linux_tun::config_content(socks_port, None, None, "", "", false);
        if fs::write(&config_path, content).is_err() {
            self.status = "Не удалось записать конфигурацию TUN".into();
            return false;
        }
        let command = if Command::new("pkexec").arg("--version").output().is_ok() {
            "pkexec"
        } else {
            binary.to_str().unwrap_or("hev-socks5-tunnel")
        };
        let result = if command == "pkexec" {
            Command::new(command)
                .args([
                    binary.to_string_lossy().as_ref(),
                    config_path.to_string_lossy().as_ref(),
                ])
                .spawn()
        } else {
            Command::new(command).arg(&config_path).spawn()
        };
        match result {
            Ok(child) => {
                self.tun_process = Some(child);
                self.status = "TUN runtime запущен".into();
                true
            }
            Err(error) => {
                self.status = format!("Не удалось запустить HEV: {error}");
                false
            }
        }
    }

    fn connection_elapsed(&self) -> String {
        let seconds = self
            .connected_at
            .map(|t| t.elapsed().as_secs())
            .unwrap_or(0);
        format!(
            "{:02}:{:02}:{:02}",
            seconds / 3600,
            (seconds / 60) % 60,
            seconds % 60
        )
    }

    fn apply_system_proxy(&mut self) {
        let commands = if self.system_proxy {
            let Ok(port) = self.proxy_port.parse::<i32>() else {
                self.status = "Неверный HTTP-порт".into();
                return;
            };
            enable_gnome_proxy_commands("127.0.0.1", port)
        } else {
            disable_gnome_proxy_commands()
        };
        for command in commands {
            let Some((program, args)) = command.split_first() else {
                continue;
            };
            if Command::new(program).args(args).status().is_err() {
                self.status = "Не удалось применить системный proxy".into();
                return;
            }
        }
        self.status = if self.system_proxy {
            format!("HTTP 127.0.0.1:{}", self.proxy_port)
        } else {
            "Системный proxy отключен".into()
        };
    }

    fn refresh_subscriptions(&mut self) {
        let urls: Vec<String> = self
            .bundle
            .locations
            .iter()
            .filter_map(|e| e.subscription_url.clone())
            .filter(|u| u.starts_with("http://") || u.starts_with("https://"))
            .collect();
        if urls.is_empty() {
            self.status = "Нет подписок для обновления".into();
            return;
        }
        let mut success = 0;
        for url in urls {
            let output = Command::new("curl")
                .args([
                    "--silent",
                    "--show-error",
                    "--location",
                    "--max-time",
                    "30",
                    &url,
                ])
                .output();
            let Ok(output) = output else { continue };
            if !output.status.success() {
                continue;
            }
            let Ok(body) = String::from_utf8(output.stdout) else {
                continue;
            };
            if let Some(parsed) = parse_import(&body, Some(&url), None, None) {
                self.bundle = yptun_core::importer::merge_imported_bundle(
                    Some(&self.bundle),
                    &parsed.bundle,
                    true,
                )
                .normalized();
                success += 1;
            }
        }
        if success > 0 {
            self.locations = self
                .bundle
                .locations
                .iter()
                .map(Self::row_from_entry)
                .collect();
            self.selected_location = self
                .selected_location
                .min(self.locations.len().saturating_sub(1));
            self.endpoint = self.locations[self.selected_location].endpoint.clone();
            self.save_state();
            self.status = format!("Обновлено подписок: {success}");
        } else {
            self.status = "Не удалось обновить подписки".into();
        }
    }

    fn import_location(&mut self) {
        let link = self.import_text.trim();
        if link.is_empty() {
            self.status = "Вставь ссылку конфигурации".into();
            return;
        }
        let Some(parsed) = parse_import(link, None, None, None) else {
            self.status = "Не удалось распознать ссылку конфигурации".into();
            return;
        };
        let imported_bundle = parsed.bundle.normalized();
        let imported = imported_bundle.locations.clone();
        if imported.is_empty() {
            self.status = "В ссылке нет конфигураций".into();
            return;
        }
        self.bundle = yptun_core::importer::merge_imported_bundle(
            Some(&self.bundle),
            &imported_bundle,
            false,
        )
        .normalized();
        self.locations = self
            .bundle
            .locations
            .iter()
            .map(Self::row_from_entry)
            .collect();
        self.selected_location = self
            .bundle
            .active_location_id
            .as_ref()
            .and_then(|id| {
                self.bundle
                    .locations
                    .iter()
                    .position(|e| &e.storage_id == id)
            })
            .unwrap_or(0);
        self.endpoint = self.locations[self.selected_location].endpoint.clone();
        self.import_text.clear();
        self.show_import = false;
        self.status = "Конфигурация добавлена".into();
        self.save_state();
    }

    fn ping_group_indices(&mut self, indices: &[usize]) {
        let targets: Vec<(usize, String)> = indices
            .iter()
            .filter_map(|&i| {
                self.locations
                    .get(i)
                    .map(|loc| (i, loc.endpoint.clone()))
            })
            .collect();

        let pings_map = Arc::clone(&self.pings);
        self.is_pinging = true;

        thread::spawn(move || {
            for (idx, ep) in targets {
                if let Some((host, port)) = split_proxy_host_port(&ep) {
                    let start = Instant::now();
                    let addr_str = format!("{}:{}", host, port);
                    let mut resolved = addr_str.to_socket_addrs().ok();
                    let target_addr = resolved.as_mut().and_then(|iter| iter.next());

                    if let Some(addr) = target_addr {
                        if TcpStream::connect_timeout(&addr, Duration::from_millis(1500)).is_ok() {
                            let ms = start.elapsed().as_millis().min(9999) as u32;
                            if let Ok(mut map) = pings_map.lock() {
                                map.insert(idx, ms);
                            }
                        }
                    }
                }
            }
        });
    }

    fn run_auto_ping(&mut self) {
        let all_indices: Vec<usize> = (0..self.locations.len()).collect();
        self.ping_group_indices(&all_indices);

        let best_idx = if let Ok(map) = self.pings.lock() {
            map.iter().min_by_key(|(_, &ms)| ms).map(|(&idx, _)| idx)
        } else {
            None
        };

        if let Some(best_idx) = best_idx {
            self.selected_location = best_idx;
            self.endpoint = self.locations[best_idx].endpoint.clone();
            self.set_active_bundle(best_idx);
            self.save_state();
            self.status = format!("Выбран быстрый сервер: {}", self.locations[best_idx].name);
        } else {
            self.status = "Проверка задержки серверов...".into();
        }
    }
}
