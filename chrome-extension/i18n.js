// Tiny dictionary i18n: follows the browser UI language by default, but the popup's language
// selector wins (the extension must speak the language the user picked, not the one Chrome runs in).
const DICT = {
  en: {
    title: "YPtun VPN",
    connected: "Connected",
    connecting: "Checking…",
    disconnected: "Disconnected",
    connect: "Connect",
    disconnect: "Disconnect",
    servers: "Locations",
    add: "Add location",
    name: "Name (optional)",
    link: "vless://… link for your location",
    save: "Save",
    cancel: "Cancel",
    remove: "Remove",
    noServers: "No locations yet — paste your vless:// link.",
    settings: "Settings",
    bypass: "Bypass list (one host per line)",
    language: "Language",
    auto: "Auto",
    via: "via",
    badLink: "Not a vless:// or https:// link",
    errAuth: "The proxy rejected the login — the server must accept the link's UUID as user and password.",
    errUnreachable: "%s does not answer. The server needs an xray http inbound on that port, and over TLS it needs a real certificate (Chrome will not accept REALITY or a self-signed one).",
    errHttp: "The proxy answered HTTP %s.",
  },
  ru: {
    title: "YPtun VPN",
    connected: "Подключено",
    connecting: "Проверка…",
    disconnected: "Отключено",
    connect: "Подключить",
    disconnect: "Отключить",
    servers: "Локации",
    add: "Добавить локацию",
    name: "Название (необязательно)",
    link: "Ссылка vless://… на вашу локацию",
    save: "Сохранить",
    cancel: "Отмена",
    remove: "Удалить",
    noServers: "Локаций пока нет — вставьте свою ссылку vless://.",
    settings: "Настройки",
    bypass: "Исключения (по одному хосту в строке)",
    language: "Язык",
    auto: "Авто",
    via: "через",
    badLink: "Это не ссылка vless:// или https://",
    errAuth: "Прокси отклонил логин — сервер должен принимать UUID из ссылки как логин и пароль.",
    errUnreachable: "%s не отвечает. На сервере нужен http-inbound xray на этом порту, а под TLS — настоящий сертификат (REALITY и самоподписанный Chrome не примет).",
    errHttp: "Прокси ответил HTTP %s.",
  },
};

export function pickLang(setting) {
  if (setting === "ru" || setting === "en") return setting;
  return chrome.i18n.getUILanguage().startsWith("ru") ? "ru" : "en";
}

export function strings(setting) {
  return DICT[pickLang(setting)];
}
