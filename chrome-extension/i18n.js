// Tiny dictionary i18n: follows the browser UI language by default, but the popup's language
// selector wins (the extension must speak the language the user picked, not the one Chrome runs in).
const DICT = {
  en: {
    title: "YPtun VPN",
    connected: "Connected",
    connecting: "Connecting…",
    disconnected: "Disconnected",
    connect: "Connect",
    disconnect: "Disconnect",
    servers: "Servers",
    add: "Add server",
    name: "Name",
    kind: "Type",
    vless: "VLESS",
    awg: "AmneziaWG",
    configVless: "vless://… link",
    configAwg: "AmneziaWG config ([Interface] … [Peer] …)",
    save: "Save",
    cancel: "Cancel",
    remove: "Remove",
    noServers: "No servers yet — add a vless:// link or an AmneziaWG config.",
    settings: "Settings",
    bypass: "Bypass list (one host per line)",
    language: "Language",
    auto: "Auto",
    hostMissing: "Native host not registered",
    hostHint: "The browser can't speak VLESS/AmneziaWG on its own. Run this once in a terminal, then reopen the popup:",
    copy: "Copy",
    copied: "Copied",
    socksPort: "SOCKS5 on 127.0.0.1:",
  },
  ru: {
    title: "YPtun VPN",
    connected: "Подключено",
    connecting: "Подключение…",
    disconnected: "Отключено",
    connect: "Подключить",
    disconnect: "Отключить",
    servers: "Серверы",
    add: "Добавить сервер",
    name: "Название",
    kind: "Тип",
    vless: "VLESS",
    awg: "AmneziaWG",
    configVless: "Ссылка vless://…",
    configAwg: "Конфиг AmneziaWG ([Interface] … [Peer] …)",
    save: "Сохранить",
    cancel: "Отмена",
    remove: "Удалить",
    noServers: "Серверов пока нет — добавьте ссылку vless:// или конфиг AmneziaWG.",
    settings: "Настройки",
    bypass: "Исключения (по одному хосту в строке)",
    language: "Язык",
    auto: "Авто",
    hostMissing: "Нативный хост не зарегистрирован",
    hostHint: "Браузер сам не умеет VLESS/AmneziaWG. Выполните это один раз в терминале и снова откройте окно:",
    copy: "Копировать",
    copied: "Скопировано",
    socksPort: "SOCKS5 на 127.0.0.1:",
  },
};

export function pickLang(setting) {
  if (setting === "ru" || setting === "en") return setting;
  return chrome.i18n.getUILanguage().startsWith("ru") ? "ru" : "en";
}

export function strings(setting) {
  return DICT[pickLang(setting)];
}
