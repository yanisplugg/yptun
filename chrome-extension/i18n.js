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
    link: "vless:// / vmess:// / trojan:// … link",
    save: "Save",
    cancel: "Cancel",
    remove: "Remove",
    noServers: "No locations yet — paste your link.",
    settings: "Settings",
    bypass: "Bypass list (one host per line)",
    language: "Language",
    auto: "Auto",
    via: "via",
    badLink: "Not a link YPtun understands (vless://, vmess://, trojan://, ss://…)",
    app: "app",
    errNotRunning: "YPtun is not running. Start the app — the extension does not carry traffic itself, it drives YPtun, which is what lets tcp, xhttp, TLS and REALITY work here.",
    errBadLink: "YPtun did not recognise this link.",
    errNoLocation: "No location to connect to — paste a link first.",
    errTimeout: "YPtun did not come up in time. Open the app and look at the log.",
    errPortBusy: "Port 47640 is taken by another program, so the extension has nowhere to send traffic.",
    errNoTraffic: "The tunnel is up but nothing goes through it. Check the location in YPtun itself.",
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
    link: "Ссылка vless:// / vmess:// / trojan:// …",
    save: "Сохранить",
    cancel: "Отмена",
    remove: "Удалить",
    noServers: "Локаций пока нет — вставьте свою ссылку.",
    settings: "Настройки",
    bypass: "Исключения (по одному хосту в строке)",
    language: "Язык",
    auto: "Авто",
    via: "через",
    badLink: "YPtun не понимает такую ссылку (vless://, vmess://, trojan://, ss://…)",
    app: "приложение",
    errNotRunning: "YPtun не запущен. Запустите приложение — расширение само трафик не носит, оно управляет YPtun, и именно поэтому здесь работают tcp, xhttp, TLS и REALITY.",
    errBadLink: "YPtun не распознал эту ссылку.",
    errNoLocation: "Подключаться не к чему — сначала вставьте ссылку.",
    errTimeout: "YPtun не поднялся вовремя. Откройте приложение и посмотрите журнал.",
    errPortBusy: "Порт 47640 занят другой программой — расширению некуда отправлять трафик.",
    errNoTraffic: "Туннель поднят, но трафик через него не идёт. Проверьте локацию в самом YPtun.",
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
