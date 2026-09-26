# Подготовка и публикация YPtun в App Store: Чек-лист и Roadmap

В этом документе пошагово описан процесс вывода приложения **YPtun** в App Store: от подготовки скриншотов до прохождения проверки цензорами Apple (App Review).

---

## Этап 1. Включение GitHub Pages в основном репозитории

Сейчас страницы (`privacy.html`, `support.html`, `index.html`) уже работают на вашем форке:  
`https://zamotashka.github.io/olcvpn-client/privacy.html`

Чтобы эти же страницы открывались по адресу основного репозитория (`yanisplugg.github.io`):
1. Владелец репозитория (`yanisplugg`) должен зайти в https://github.com/yanisplugg/olcvpn-client
2. Открыть **Settings** ➔ вкладка **Pages**.
3. В разделе **Build and deployment**:
   - **Source:** *Deploy from a branch*
   - **Branch:** `feat/testflight-deploy` (или `main`)
   - **Folder:** `/docs`
   - Нажать **Save**.
*(Причина, почему бот не смог переключить это сам: GitHub API запрещает менять системные настройки чужого репозитория без прав Admin, хотя коммитить и пушить код разрешено).*

---

## Этап 2. Скриншоты для App Store (Требования и рекомендации)

### Обязательные размеры экранов:
- **6.9" / 6.7" iPhone (Обязательно):** `1290 x 2796` px (iPhone 15 Pro Max / 16 Pro Max).
- **6.5" iPhone (Рекомендуется):** `1242 x 2688` px (iPhone 11 Pro Max / XS Max).

### 4 рекомендуемых скриншота:
1. **Скриншот 1: Главный экран (Подключено)**
   - Большая кнопка с активным статусом «Подключено / Connected», сервер (например, Германия/Финляндия), низкий пинг (35–45 ms), счетчик времени сессии.
   - *Текст на плашке сверху:* «Безопасное соединение в одно касание» / *«One-tap Secure Connection»*.
2. **Скриншот 2: Список локаций и пинг-тест**
   - Список серверов с флагами стран и зелеными миллисекундами пинга.
   - *Текст на плашке сверху:* «Выбор быстрых серверов и мгновенный пинг» / *«Low Latency Server Selection»*.
3. **Скриншот 3: Пункт управления и Dynamic Island**
   - Экран с открытым Пунктом управления iOS 18 (кнопка с силуэтом кота) и Dynamic Island.
   - *Текст на плашке сверху:* «Управление из Пункта управления и Dynamic Island» / *«Control Center & Live Activity»*.
4. **Скриншот 4: Умная маршрутизация**
   - Экран настроек с опциями «Обход LAN» и «Россия напрямую».
   - *Текст на плашке сверху:* «Умная маршрутизация локального трафика» / *«Smart Traffic Routing»*.

### ❌ Что СТРОГО ЗАПРЕЩЕНО на скриншотах:
- Логотипы заблокированных соцсетей (Instagram, Twitter/X, Facebook и т.д.) — мгновенный реджект за товарные знаки и политику.
- Открытые сайты в Safari.
- Слова: «РКН», «Обход блокировок», «Антицензура», «Торренты», «Разблокировка сайтов».

---

## Этап 3. Анкета конфиденциальности (App Privacy Nutrition Labels)

В App Store Connect при создании новой версии Apple спросит: **«Собирает ли ваше приложение данные пользователей?»**
- **Ответ:** **«No, we do not collect data from this app»** (Нет, данные не собираются).
- Это избавит вас от необходимости заполнять десятки опросников по трекингу и покажет в App Store красивый значок «Data Not Collected» (Данные не собираются), что вызывает максимальное доверие пользователей.

---

## Этап 4. Вопрос про шифрование (Export Compliance Information)

В App Store Connect появится вопрос: **«Использует ли приложение шифрование?»**
1. Ответьте: **Yes** (Да).
2. На второй вопрос: *«Соответствует ли приложение исключениям из категории 5, части 2?»*  
   Ответьте: **Yes** (Да).  
   *(Используемое стандартное шифрование AES-GCM, ChaCha20-Poly1305 и TLS освобождено от экспортного контроля США по стандарту Category 5 Part 2 Exemptions).*
3. В файл `Info.plist` уже добавлен стандартный ключ `<key>ITSAppUsesNonExemptEncryption</key><false/>`, поэтому диалоговое окно может даже не появиться.

---

## Этап 5. Заметки для проверяющего модератора (Review Notes)

Перед отправкой на модерацию в поле **App Review Information (Notes)** укажите:

```text
Hello Apple App Review Team,

YPtun is a client-side network utility designed for secure connection diagnostics and encrypted tunneling.

Instructions for testing:
1. Launch YPtun on your device.
2. Navigate to the Locations tab and tap the "+" button in the top right.
3. Select "Paste" and import our test server configuration:
   [ВСТАВИТЬ РАБОЧУЮ ТЕСТОВУЮ VLESS/WIREGUARD ССЫЛКУ]
4. Return to the main screen and tap "Connect".
5. The VPN will establish an encrypted connection and display latency and traffic metrics.

If you have any questions or require additional information, please contact us at yptunn@gmail.com. Thank you!
```

> **Важно:** Сервер в тестовой ссылке должен быть стабильным и расположенным за пределами РФ (например, в Германии, Нидерландах или США), чтобы американский модератор Apple мог успешно подключиться к нему и увидеть зеленый статус.
