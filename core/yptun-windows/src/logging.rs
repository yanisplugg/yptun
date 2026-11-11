use std::path::Path;

use yptun_core::desktop_app::{append_log_to, format_timestamp, now_ms, FILE_LOG_NAME};

pub fn append(app_data_dir: &Path, message: &str) {
    let _ = std::fs::create_dir_all(app_data_dir);
    let _ = append_log_to(
        &app_data_dir.join(FILE_LOG_NAME),
        &format_timestamp(now_ms()),
        message,
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn log_is_written_to_the_supplied_data_directory() {
        let root = std::env::temp_dir().join(format!(
            "yptun-windows-log-test-{}-{}",
            std::process::id(),
            now_ms()
        ));
        append(&root, "portable-log");
        let text = std::fs::read_to_string(root.join(FILE_LOG_NAME)).unwrap();
        assert!(text.contains("portable-log"));
        let _ = std::fs::remove_dir_all(root);
    }
}
