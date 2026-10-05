# WatchDrop

WatchDrop — двусторонняя передача файлов между Android-телефоном и Wear OS-часами через Bluetooth Classic RFCOMM.
Один APK устанавливается на оба устройства.

## Возможности

- телефон → часы и часы → телефон;
- системное меню `Поделиться → WatchDrop` (`ACTION_SEND` и `ACTION_SEND_MULTIPLE`);
- отправка нескольких файлов за одну передачу;
- защищённое RFCOMM-соединение с собственным UUID;
- работа с уже сопряжёнными Bluetooth-устройствами;
- фоновый приём через foreground service типа `connectedDevice`;
- запоминание последнего устройства;
- потоковая передача без загрузки файла целиком в RAM;
- подтверждение каждого принятого файла;
- фото → `Pictures/WatchDrop`;
- видео → `Movies/WatchDrop`;
- аудио → `Music/WatchDrop`;
- остальные файлы → `Download/WatchDrop`;
- автозапуск приёмника после перезагрузки, если приём был включён.

## Интерфейс

На Wear OS используется `androidx.wear.compose:compose-material3:1.7.0` — Wear Material 3 / Material 3 Expressive.
Экран часов построен на `AppScaffold`, `ScreenScaffold` и `TransformingLazyColumn`, использует динамическую цветовую схему Wear OS.

На обычном Android используется Jetpack Compose Material 3 с Material You dynamic color.

## Требования

- Android / Wear OS: API 30+;
- compileSdk 37;
- targetSdk 36;
- JDK 17+;
- Android Gradle Plugin 9.4.0;
- Gradle 9.6.0;
- Android SDK Platform 37;
- Android SDK Build Tools 36.0.0.

## Сборка в Android Studio

1. Откройте каталог `WatchDrop` как проект.
2. Убедитесь, что установлены Android SDK Platform 37 и Build Tools 36.0.0.
3. Выполните Gradle Sync.
4. Выберите `Build > Build APK(s)`.
5. Debug APK будет находиться в:

   `app/build/outputs/apk/debug/app-debug.apk`

## Автоматическая сборка GitHub Actions

В проект включён workflow `.github/workflows/build-apk.yml`.
После загрузки проекта в GitHub он автоматически собирает debug APK при push в `main`/`master`; его также можно запустить вручную через `Actions > Build WatchDrop APK > Run workflow`.
Готовый APK появляется как artifact `WatchDrop-debug-apk`.

## Установка

Установите один и тот же APK на телефон и часы. Устройства предварительно должны быть сопряжены обычными средствами Bluetooth.

На телефоне:

```bash
adb install -r app-debug.apk
```

На часах по Wireless debugging:

```bash
adb pair WATCH_IP:PAIR_PORT
adb connect WATCH_IP:ADB_PORT
adb install -r app-debug.apk
```

После первого запуска разрешите доступ к Bluetooth / Nearby devices. На обоих устройствах оставьте включённым фоновый приём.

## Использование

На телефоне:

`Галерея / Файлы → Поделиться → WatchDrop → Galaxy Watch`

На часах:

`приложение с поддержкой Sharesheet → Поделиться → WatchDrop → телефон`

Кроме Sharesheet, на главном экране WatchDrop есть кнопка выбора файлов через системный document picker. Наличие системного document provider зависит от прошивки Wear OS; Sharesheet не зависит от этой кнопки.

## Ограничение

WatchDrop не добавляет в Wear OS системный Bluetooth Object Push Profile (OPP). Обычное стороннее приложение не может зарегистрировать системный OPP-приёмник так же, как системный Bluetooth-пакет. Поэтому WatchDrop использует собственный RFCOMM-протокол и должен быть установлен на обоих Android/Wear OS устройствах.
