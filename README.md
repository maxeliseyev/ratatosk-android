# Ratatosk для Android

Android-клиент на том же Rust-ядре, что и desktop/iOS Ratatosk, по образу и
подобию [`ratatosk-ios`](../ratatosk-ios). Это VPN-приложение: `VpnService`
поднимает системный туннель, а всё остальное — REALITY, Vision, XHTTP/WS,
routing, FakeDNS, подписки — делает `ratatosk-core`.

Основной репозиторий ([`../ratatosk`](../ratatosk)) **не меняется**: JNI-мост
живёт в `rust/` этого репозитория и зависит от `crates/core` по path, как
iOS-репозиторий зависит от `crates/ffi`.

## Как это устроено

```
┌────────────── MainActivity (Jetpack Compose) ───────────────┐
│ серверы, ссылка/QR, подписки, подключение, статистика, пинг │
│                    TunnelController (singleton)              │
└───────────────────────────┬─────────────────────────────────┘
                            │ Intent (link, direct_zones)
┌───────────────────────────▼─────────────────────────────────┐
│ RatatoskVpnService : VpnService                              │
│   Builder.establish() ──► TUN fd ──► JNI nativeStart         │
│   (foreground + network callback)                            │
└───────────────────────────┬─────────────────────────────────┘
                            │ libratatosk_android.so
┌───────────────────────────▼─────────────────────────────────┐
│ Rust (rust/src/lib.rs)                                       │
│   read/poll(TUN fd) ──► userspace netstack ──► ProxyClient   │
│   netstack ──► write(TUN fd)                                 │
│   ratatosk-core: REALITY, Vision, Mux, XHTTP, routing, DNS   │
└──────────────────────────────────────────────────────────────┘
```

Отличия от iOS (там Packet Tunnel extension + `NEPacketTunnelFlow`):

| | iOS | Android |
|---|---|---|
| Системный VPN | `NEPacketTunnelProvider` | `VpnService` + TUN fd |
| Пакеты | Swift гоняет `readPackets`/`writePackets` | Rust сам читает/пишет fd |
| Обмен с UI | `sendProviderMessage` (cross-process) | общий процесс, `TunnelController` |
| Хранилище секретов | Keychain | `EncryptedSharedPreferences` |
| QR | VisionKit | CameraX + zxing |
| Сборка ядра | `build-xcframework.sh` | `scripts/update-core.sh` (NDK, 3 ABI) |

Почему Rust владеет fd: `VpnService.Builder.establish()` даёт
`ParcelFileDescriptor`; приложение делает `detachFd()` и передаёт сырой fd в
`nativeStart`. Ядро dup‑нет его себе, `poll()`‑ит входящие пакеты в netstack и
пишет исходящие обратно. Никаких JNI-вызовов на каждый пакет.

## Структура

```
app/src/main/java/dev/ratatosk/
  core/RatatoskCore.kt      JNI-поверхность (System.loadLibrary)
  core/RatatoskTools.kt     ping, подписки, разбор stats/flows, human()
  data/Models.kt            Server, SubscriptionEntry (имя/тип из ссылки)
  data/ServerStore.kt       EncryptedSharedPreferences
  vpn/RatatoskVpnService.kt  VpnService: TUN, foreground, сеть
  vpn/TunnelController.kt    состояние, подключение, пинг, подписки
  ui/RatatoskScreen.kt       сам экран (моно, монospace)
  ui/QrScanner.kt            CameraX + zxing
rust/                       Rust-крейт: cdylib + JNI
scripts/update-core.sh      сборка .so под Android, копия в jniLibs
scripts/build-apk.sh        сборка APK
```

`rust/src/lib.rs` повторяет `crates/ffi/src/lib.rs::start` из основного репо
(инициализация `ProxyClient`/`Netstack`/`FakeDns`), только транспорт пакетов —
fd, а не callback хоста. При изменениях ядра сверяйтесь с `crates/ffi`.

## Что уже есть

- Локальный/системный VPN через `VpnService`, foreground-уведомление,
  исключение собственного приложения из туннеля (`addDisallowedApplication`),
  IPv4 и IPv6 по умолчанию, MTU 1500.
- Ссылки `vless://`, `h3://`, `vpn://`; подписки `http(s)://` с обновлением.
- Список серверов, выбор активного, `ping all`, `best` (как в iOS).
- Живой лог соединений (route/хост/трафик) и счётчики.
- Сплит-туннель «RU direct» (`.ru .su .рф` напрямую).
- Смена сети: `ConnectivityManager` → `nativeNetworkChanged`.
- QR-сканер и вставка из буфера.

## Установка ссылки по adb (удобно при отладке)

`MainActivity` принимает ссылку через intent, поэтому сервер можно добавить
без ручного ввода:

```sh
adb shell am start -n dev.ratatosk/.MainActivity \
  --es link 'h3://UUID@host:443?sni=host#name'
```

Так же работают обычные `vless://`, `h3://`, `vpn://` по тапу (intent-фильтр
`VIEW`): ссылку можно открыть из мессенджера или браузера.

## Проверено на устройстве

- Redmi Note 8 Pro (`begonia`, arm64-v8a, Android 11 / API 30): сборка
  устанавливается, VPN поднимается, значок VPN в статус-баре.
- `h3://` боевой сервер: `ping all` дал `1406 ms`, после `connect` пошёл
  реальный трафик (facebook, telegram, `8.8.8.8 udp:53`), статистика и лог
  потоков обновляются, `api.ipify.org` показал IP выхода сервера.
- На десктопе (та же сеть) QUIC до сервера не проходил — мобильная сеть
  достучалась, то есть UDP в сети Mac резался.

## Сборка

Нужны: Rust stable с Android-таргетами, Android NDK, Android SDK (platform 36),
JDK 17+.

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

# 1. Собрать ядро под Android (все 3 ABI) и положить в app/src/main/jniLibs
RATATOSK_DIR=../ratatosk scripts/update-core.sh

# 2. Собрать APK
./gradlew :app:assembleDebug
# или всё сразу (ядро + APK):
scripts/build-apk.sh            # debug
scripts/build-apk.sh --release  # release
```

Переменные: `ANDROID_NDK_HOME` (иначе берётся новейший NDK из SDK),
`ANDROID_HOME`, `RATATOSK_DIR`, `ABIS="arm64-v8a x86_64"`.

`local.properties` с `sdk.dir` не коммитится — создайте под свою машину.

## Ограничения и что дальше

- Версия — ранний прототип; проверки на живом устройстве и на боевом сервере
  ещё не было.
- Нет своего экрана логов ядра (логи идут в logcat, тег `ratatosk`).
- Release APK пока без подписи и без minify.
- `x86` (32-бит) не собирается — старые эмуляторы не поддерживаются.
- Стоит сверить память под нагрузкой (на iOS лимит extension ~50 МБ) и
  Wi-Fi ↔ LTE на реальном устройстве.
