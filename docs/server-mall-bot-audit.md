# Аудит Server-Mall-Bot (VladimirZen/Server-Mall-Bot)

Дата: 2026-09-04. Проверенный коммит: `6ff8992` (Quote the shape a customer compares to instead of refusing the lead).

Задача продукта: менеджер отправляет боту текст запроса, получает три ценовых предложения (DIRECT / ALTERNATIVE / VALUE), проверяет, `/approve` даёт клиентскую версию.

## Как проверял и что это ограничивает

- Код прочитан по цепочке `main -> bot/handlers -> engine -> new_path/service -> parsing/completion/clarification -> planning/d1 -> pricing -> presentation`.
- Книги цен `data/1.1 Configurator.xlsx` в репозитории нет, без неё бот и большая часть тестов не запускаются. Для сквозных прогонов я собрал синтетическую книгу: все 1110 позиций каталога с правдоподобными ценами по категориям (REF = 40% от NEW). Абсолютные суммы в примерах ниже фиктивные, структура КП, роли, состав BOM и статусы настоящие.
- Реальные закупочные цены, маржа по реальным данным и покрытие «что реально в наличии» этим аудитом не проверены.
- Ruff: чисто. Собирается 1828 тестов. Прогон подмножества без книги цен см. в конце.

## Вердикт

Ядро (парсер, сборка BOM, три роли, статусы цен, клиентская выдача) работает на чистых английских запросах вида «Dell R760 8SFF NEW, 2x Xeon Gold 6430, 256GB DDR5, 4x 1.92TB SAS SSD RAID10» и выдаёт три осмысленные роли. Но есть шесть дефектов, каждый из которых по отдельности ломает сценарий «скинул описание, получил 3 КП» в реальной работе: русский текст даёт уверенное неверное КП, ответ на уточняющий вопрос теряется, бот блокируется на 7–19 с на каждый запрос, ввод цены вида «1,250» отравляет ценовой журнал навсегда, 25GbE не собирается ни на одной платформе, а без Excel бот не стартует вообще.

## Критические

### 1. Русский запрос даёт «готовое» КП с урезанной спецификацией

Запрос: `Нужен сервер Dell R760, 2 процессора Xeon 6430, 256 ГБ ОЗУ, 4 диска 1.92 ТБ SSD`.

Результат: статус COMPLETE, `ready=True`, `/approve` разрешён. В DIRECT: 1 CPU вместо 2, 2× 32GB (64 ГБ) вместо 256, дисков нет. Deviation нет.

Причина: парсер знает только английские единицы и количества («2x», «GB», «TB»). Кириллические «ГБ», «ТБ», «процессора», «диска» пропускаются, и completion подставляет дефолты (`default_min_cpu_cores=8`, 64 ГБ). Ни проверки языка, ни проверки «доля текста, которую парсер не понял», нет. Контракт из handoff §3.1 («никогда не урезать молча CPU, RAM, хранилище») нарушен для любого запроса не на английском.

Где: `parsing/extractors/*`, `completion/engine.py`, `clarification/policy.py`.

Минимальная защита: если в тексте есть кириллица или числа с единицами, которые не превратились ни в один Hit, задавать вопрос или отвечать «пришлите на английском», а не строить план.

### 2. Ответ на уточняющий вопрос стартует новый запрос и теряет контекст

`handle_clarification_answer` (bot/handlers.py) перед `apply_clarification` вызывает `new_path.looks_like_full_rfq(text)`. Эта функция (new_path/service.py:138-147) возвращает True, если распарсилась модель, CPU или объём RAM.

Наблюдал: `Dell R760` -> True, `256GB` -> True, `64 GB` -> True, `Xeon Gold 6430` -> True. То есть если бот спросил «какая модель?» или «сколько памяти?», короткий ответ менеджера обрабатывается как новый RFQ из одного слова, исходный текст запроса выбрасывается.

Исправление: в состоянии `waiting_for_answer` считать текст новым запросом только если он длиннее порога и содержит две и более независимые категории, либо дать явную команду `/new`.

### 3. Планировщик синхронный и блокирует весь бот

`new_path.process_text`, `apply_clarification`, `apply_manual_price`, `approve` вызываются из async-хендлеров напрямую, без `asyncio.to_thread`/executor. Замеры на синтетической книге:

| Запрос | Время |
|---|---|
| R760 + 6430 + 256GB, без дисков | 15.6 с |
| то же + 4x 1.92TB SAS RAID10 | 8.1 с |
| виртуализация 512GB + 20TB usable | 7.4 с |
| русский запрос (дефолты) | 18.9 с |

Всё это время aiogram не обрабатывает ни одно сообщение от других менеджеров и не отвечает на `/stop`. Причины удвоения: `_plan_request` (service.py:388-411) всегда прогоняет планировщик дважды (allow_price_pending False, затем True), если первый прогон не COMPLETE. `apply_manual_price` (service.py:222) пересобирает планировщик и заново читает Excel через openpyxl при каждой введённой цене.

Исправление: `await asyncio.to_thread(...)` для всех вызовов сервиса, кеш прочитанной книги, один прогон планировщика с обоими режимами.

### 4. Ввод цены: «1,250» становится 1.25 и уходит в журнал с высшим приоритетом навсегда

`_parse_price` и `_parse_sku_and_price` (bot/handlers.py:45-67) заменяют запятую на точку. Наблюдал:

| Ввод | Распознано |
|---|---|
| `1,250` | 1.25 |
| `1,250.00` | 1.25 |
| `1.250,00` | 1.25 |
| `$1 200` | sku=`1`, price=200 |
| `USD 180` | sku=`USD`, price=180 |
| `Dell R760 8SFF, 2x Xeon 6430, 256GB RAM` | 7608 |

Последняя строка: менеджер в состоянии `waiting_for_price` присылает новый запрос, а бот предлагает подтвердить цену 7608 USD. В `handle_manual_price` нет защиты `looks_like_full_rfq`, которая есть в `handle_clarification_answer`.

После «yes» цена пишется в `journal.set_catalog_price` по display name, а `ManagerPurchaseDbSource` стоит первым в приоритете источников (service.py:529-532). Команды исправить или удалить цену нет, срока годности нет. Одна опечатка занижает или завышает все будущие КП с этим компонентом, и ни один менеджер этого не увидит, потому что `/sources` покажет «manager-confirmed purchase database».

Исправление: разделители тысяч по локали, отказ от разбора текста без явного числа, `/prices` только через кнопки или формат `SKU PRICE`, команда отката, срок действия цены и пометка в `/sources` кто и когда ввёл.

### 5. 25GbE SFP28 не собирается ни на одной платформе

Регулярное выражение `_PORT` в `parsing/extractors/network.py:12`: `(?P<media>rj-?45|sfp\+?|sfp28|base-t)`. Альтернатива `sfp\+?` совпадает с `sfp` внутри `sfp28` раньше, чем `sfp28`, и media нормализуется в `SFP+`. Затем `nic_media_matches` (`compatibility/id_engine.py:100`) отвергает все SFP28-карты. Итог: `... 2x 25GbE SFP28` -> `engineering_candidates=0` на всех 45 платформах, CATALOG_COVERAGE_GAP, и для Dell, и для HPE.

Без слова SFP28 (`2x 25GbE`): у HPE Gen11 находится OCP3-карта, но у Dell NEW-платформ 25G-карт нет совсем: четыре NEW 25G карты в каталоге это HPE (P-номера) и помечены как OEM HPE, «Dell Mellanox ConnectX-4 2x25G NDC» не имеет `speed_gb`, «Mellanox ConnectX-4 Lx 25GbE» не имеет `form`. В итоге `Dell R760 + 2x 25GbE` -> NO_VALID_OPTIONS.

В `resources/compatibility_relations.yaml` 0 из 61 сетевых карт имеют отношения к платформам, совместимость NIC проверяется только по форм-фактору из `compatibility_curation.yaml`, без проверки наличия OCP/NDC-слота на конкретной платформе.

### 6. Без Excel бот не запускается, а CI без секрета падает

`create_dispatcher` (bot/handlers.py) безусловно создаёт `NewPathCPQService`, чей `_build_planner` вызывает `ConfiguratorPriceSource.records()` -> `load_workbook` -> `FileNotFoundError`. Это происходит независимо от `CPQ_NEW_PATH_ENABLED`. Handoff §17 упоминает, что `.env` на проде когда-то содержал устаревшие пути, значит одна опечатка в `SERVERMALL_EXCEL_PATH` кладёт бота при рестарте.

В CI (`.github/workflows/test.yml`) книга приходит из секрета, а тесты `test_e2_planner`, `test_e25_new_platforms`, `test_f2_e2e`, `test_f1_completion_e2e`, `test_mvp_acceptance_corpus` и другие вызывают `ConfiguratorPriceSource(Path("data/1.1 Configurator.xlsx"))` без `skipif`, то есть падают, а не пропускаются. Полный сьют по handoff идёт около 3 часов, и в handoff §11 прямо сказано, что достоверного полностью зелёного прогона на финальном коммите нет.

## Высокие

### 7. NEW-предложение содержит REF-диски без пометки

Все 62 позиции категории storage в `normalized_catalog.yaml` имеют `condition: REF`. В DIRECT для `Dell R760 8SFF NEW ... 4x 1.92TB SAS SSD` строка `cmp_storage_1920_0_sas_sff_4b05479fc7` (REF-диск) попадает в NEW-сервер без deviation, в клиентском КП строка выглядит как `4× SSD 2.5" 1.92TB SAS` рядом с `(NEW)` позициями. Либо каталог неверно помечает диски, либо клиент получает «новый» сервер с б/у дисками без раскрытия. Handoff §14: «NEW request must not silently receive REF».

### 8. Несогласованные BOM между ролями: у DIRECT нет салазок

Тот же запрос: DIRECT (R760, 4 диска) без строки Tray, ALTERNATIVE (DL360 Gen11) и VALUE (R6525) с 4 салазками. Либо DIRECT занижен, либо остальные завышены. `d1/builder.py` добавляет tray только когда `get_compatible_trays` возвращает результат, поэтому отсутствие совместимой салазки в данных молча превращается в «салазки не нужны».

### 9. NVMe RAID10 на R760 8SFF даёт catalog gap

`Dell R760 8SFF NEW, 2x Xeon Gold 6430, 256GB DDR5, 4x 1.92TB NVMe RAID10` -> `fully_priced_candidates=4, post_diversity_candidates=0` -> NO_VALID_OPTIONS. Четыре собранных и оценённых кандидата отбрасываются фильтром разнообразия и менеджер получает «This configuration is not in the commercially supported catalog» на один из самых типовых запросов. Фильтр разнообразия должен деградировать до DEGRADED с двумя ролями, а не до пустого ответа.

### 10. Дефолты CPU коммерчески неправдоподобны

`Need a virtualization server, 512GB RAM, 20TB usable SSD storage, 2 CPUs` -> DIRECT: 2× Xeon Bronze 3408U (8 ядер), VALUE: 2× Xeon Silver 4112 (4 ядра). Профиль виртуализации даёт `default_min_cpu_cores=8` на сервер, и планировщик берёт самый дешёвый CPU, проходящий порог. Клиент с 512 ГБ RAM под виртуализацию получит КП с 16 ядрами на два сокета. Нужны пороги по workload на ядро/сокет и минимальный tier (Silver/Gold), иначе VALUE и DIRECT выигрывают по цене за счёт бессмысленной конфигурации.

### 11. Квотирование без требований

`need a server` -> вопрос про назначение -> ответ `I don't know` -> полноценное COMPLETE КП на Dell R760xs. Бот не должен выдавать три цены на пустой запрос, это создаёт ложную уверенность у менеджера и у клиента.

### 12. Прод-доступ раскрыт в публичном репозитории

`deploy/deploy.py:21-22`: `HOST = "79.132.139.8"`, `USER = "root"`, вход по паролю из `DEPLOY_SSH_PASSWORD`, `paramiko.AutoAddPolicy()` (без проверки host key). Тот же IP и политика в 11 других скриптах (`deploy/*.py`, `scripts/sync_versions.py`, `scripts/cutover_*.py`, `scripts/f37_*.py`, `scripts/audit_controlled_real_rfq_replay.py`). Handoff §17 признаёт, что «credentials have been shared interactively in the project history». Токен бота и пароли в дереве и истории последних 50 коммитов не найдены. Рекомендуется: ключи вместо пароля, отключить root-login, host в переменной окружения, известный host key, ротация пароля.

### 13. Состояние диалога живёт в памяти процесса

`Dispatcher()` без storage -> MemoryStorage. `request_id`, `pending_prices`, `pending_price_confirmation` теряются при каждом рестарте и деплое. Сессии new_path сохраняются в journal, но FSM после рестарта их не знает, менеджер получает «The session expired». `deploy/deploy.py` рестартует сервис при каждом деплое.

### 14. Флаг прод-пути выключен по умолчанию, README описывает другой продукт

`config.py`: `cpq_new_path_enabled = False`, `.env.example`: `CPQ_NEW_PATH_ENABLED=false`, при этом коммит c214c72 и handoff §7 объявляют new path производственным. На свежем окружении или при потере `.env` бот молча запускает legacy-путь `simple/` (файлы, OCR, другая логика). README, `/quote` и `/start` описывают PDF/DOCX/XLSX и «Exact/Alternative/REF», хотя прод путь только текст и DIRECT/ALTERNATIVE/VALUE.

### 15. Доступ открыт всем при дефолтном `APP_ENV`

`bot/access.py`: если `APP_ENV` не `production`/`prod`, пустой allowlist пускает всех. Дефолт `development`. Забытая переменная на сервере открывает закупочные цены (через `/sources`) любому пользователю Telegram. Middleware также отвечает каждому неавторизованному сообщению и подключён только к `dp.message`, callback-и не защищены (сейчас их нет).

## Средние

16. **Docker сломан.** `Dockerfile` делает `COPY templates ./templates`, папки `templates` в репозитории нет, `docker build` падает. `docker-compose.yml` монтирует тот же несуществующий каталог. Прод идёт через systemd, Docker-путь мёртв, но остаётся в README.

17. **Ошибки уходят в чат как есть.** `_safe_process` шлёт `Processing error: {exc}` с текстом исключения (пути, SQL, значения).

18. **Курс валют захардкожен.** `FX_USD_EUR=0.92` в env, цены журнала «в USD», выдача в EUR. Нет даты курса, нет проверки, что курс не устарел.

19. **Кириллица и цены внутри меток каталога.** `Intel Xeon Silver 4112 (4C 8.25M Caсhe ...)` с кириллической «с» в «Cache», `DELL PE T160 Chassis ... 1440eur` заведено как accessory из «Extra 1». Это объясняет, почему T160 для планировщика «нет в каталоге» (handoff §10.2). Нужна проверка меток на не-ASCII и вынос цен из названий.

20. **CI не гейтит.** Workflow только на push/PR в main, ruff не запускается, полный сьют ~3 ч, при отсутствии секрета e2e-тесты падают, handoff говорит не верить зелёным заявлениям. Практически релиз проверяется вручную скриптом `pre_deploy.ps1` на одной Windows-машине.

21. **Персональные данные в тестах.** `tests/test_channel_rfqs.py:21` содержит `marco.rossi@nexusmedia.ro` (домен реальной компании), остальные адреса `example.com`/`acme.com`. Убедиться, что это вымышленный адрес, иначе удалить.

22. **Мёртвый код и зависимости.** `simple/` (около 3.5 тыс. строк), `shadow/`, `extraction/`, `models/` используются только legacy-путём; `pdfplumber`, `python-docx`, `pillow`, `pytesseract` ставятся на прод при контракте «только текст». Пятьдесят JSON/MD аудитов в `docs/` регенерируются скриптами и создают шум в диффах.

23. **Наблюдаемость.** В логах нет `user_id`/`request_id` корреляции, structlog настроен, но хендлеры пишут через stdlib `logging`; при ошибке планировщика менеджер видит «Processing error», а в журнале нет метрик времени ответа.

## Что работает (проверено)

- Ruff: чисто.
- HTML-экранирование: `<15000 EUR & fast delivery` в запросе не ломает Telegram HTML.
- Клиентское КП (`render_client_proposal`) не содержит закупочных цен, внутренних ID `cmp_*`, кодов допущений и слов pending.
- `approve` блокируется при PRICE_PENDING, при HARD-ошибке валидации и при несовпадении итогов.
- Приоритет статусов PRICE_PENDING над CATALOG_GAP реализован (service.py:388-411) как описано в handoff §10.1.
- `Dell R760 8SFF NEW, 2x Xeon Gold 6430, 256GB DDR5, 4x 1.92TB SAS SSD RAID10` -> DIRECT Dell R760 NEW, ALTERNATIVE HPE DL360 Gen11 NEW с deviation `preferred_vendor_not_used`, VALUE Dell R6525 REF с `older_generation_value_option`. Роли и OEM-диверсификация соблюдены.
- `2x 10GbE SFP+` собирается на Dell и HPE.
- Уточняющий вопрос «What will the server primarily be used for?» задаётся на пустом запросе, и ответ `virtualization` доводит до COMPLETE.

## Слепые зоны, которые аудит не закрыл

- Реальная книга цен: маржа, дубли, конфликты цен, устаревшие позиции.
- Полный прогон 1828 тестов (около 3 часов по handoff).
- Живой Telegram: длинные ответы разбиваются `_split_for_telegram` по 4090 символов внутри HTML, теоретически можно разорвать тег; на синтетических планах ответы короче лимита.
- Работа `/prices` с реальным журналом на проде и накопленные в нём ручные цены.

## Что исправлено в этой сессии

Доступа на запись в `VladimirZen/Server-Mall-Bot` у сессии нет, поэтому исправления лежат рядом как патч `docs/server-mall-bot-audit/0001-Audit-2026-09-04-*.patch` (один коммит поверх `6ff8992`). Применить:

```bash
cd Server-Mall-Bot
git checkout -b audit-fixes-2026-09 6ff8992   # или main, если он не ушёл дальше
git am /path/to/0001-Audit-2026-09-04-refuse-non-English-RFQs-keep-clarif.patch
pytest tests/test_audit_2026_09_regressions.py tests/test_catalog_labels.py -q
```

Патч закрывает пункты 1, 2, 3, 4, 5 (парсер), 6 (сообщение при старте), 16 и частично 14 (тексты `/start`, `/quote`):

- `new_path/service.py`: кириллица в запросе или в ответе на уточнение даёт `NON_ENGLISH_INPUT` вместо урезанного плана; `looks_like_full_rfq` требует минимум два независимых признака спецификации; публичные методы под `RLock`.
- `bot/handlers.py`: планировщик вызывается через `asyncio.to_thread`; разбор цены понимает `1,250`, `1 250`, `1.250,00`, `12,50` и отвергает свободный текст; новый запрос в состоянии ввода цены стартует новое КП, а не подтверждение цены.
- `parsing/extractors/network.py`: `sfp28` раньше `sfp\+?`, 25GbE SFP28 на HPE Gen11 теперь собирается.
- `catalog/labels.py`: скорость NIC читается из «2x25G». Каталог надо перегенерировать реальной книгой (`scripts/build_catalog_attrs.py`, `scripts/build_normalized_catalog.py`), до этого `test_catalog_attrs_matches_the_workbook` будет падать на строке Dell Mellanox, это ожидаемо.
- `main.py`: понятная ошибка при отсутствии Excel вместо traceback.
- `Dockerfile`, `docker-compose.yml`: убран несуществующий `templates`.
- `docs/AUDIT_2026-09-04.md` внутри проекта: те же находки на английском, что исправлено, что открыто, что делать дальше; в коде каждая правка помечена комментарием `AUDIT 2026-09-04 #N`; `CLAUDE.md` ссылается на этот документ.
- `tests/test_audit_2026_09_regressions.py`: 40 регрессионных тестов, не требуют книги цен.

Проверка на патче: ruff чист; новые тесты 40/40; `test_f2_parser`, `test_f3_clarification`, `test_f1_completion`, `test_access`, `test_normalized_catalog`, `test_catalog_labels`, `test_new_path_sticky_state_full_rfq_wins` — 369 прошли. Полный сьют и всё, что зависит от реальных цен, не прогонялись.

## Приоритет исправлений

1. Языковая защита и защита от «непонятых чисел» (пункт 1) плюс запрет квотирования без требований (11).
2. `looks_like_full_rfq` только вне состояния уточнения и с более строгим порогом (2), та же защита в `handle_manual_price` (4).
3. Парсер цен: разделители, запрет разбора свободного текста, команда отката и срок действия ручных цен (4).
4. `asyncio.to_thread` вокруг сервиса и кеш книги (3).
5. Регулярное выражение SFP28 и атрибуты 25G-карт Dell (5), проверка салазок и REF-дисков в NEW (7, 8), деградация фильтра разнообразия (9).
6. Убрать IP/root/пароль из скриптов, ключи и host key (12); `APP_ENV=production` по умолчанию или отказ стартовать без явного значения (15); включить new path по умолчанию и починить README/Dockerfile (14, 16).
7. Персистентный FSM storage (SQLite/Redis) и ленивое построение планировщика с понятной ошибкой вместо падения при старте (6, 13).
