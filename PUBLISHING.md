# Публікація `pingwindot_notifications` на pub.dev

Покрокова інструкція для першої публікації цього плагіна.

## Передумови

- Встановлений Flutter SDK (3.3 або новіший) — перевір `flutter --version`.
- Залогінений на pub.dev акаунт (ти вже зробив).
- Папка `pingwindot_notifications/` (цей плагін) на твоїй машині.

## Крок 1. Перевірити, що ім'я пакета вільне

Pub.dev вимагає унікальні імена. Відкрий у браузері:

```
https://pub.dev/packages/pingwindot_notifications
```

Якщо сторінка показує «Package not found» (або аналог) — ім'я вільне. Якщо
вже зайнято — обери інше і дай знати, я перейменую перед публікацією.

Альтернативи на випадок зайнятого імені (по черзі):
1. `pingwin_notifications`
2. `dmon_pingwin_notifications`
3. `pingwindot_fcm_actions`

## Крок 2. Авторизуватися в pub з CLI

Один раз у термінал:

```bash
flutter pub login
```

Якщо вже логінився раніше — повторно не треба.

## Крок 3. Зайти у папку плагіна і зробити dry-run

```bash
cd pingwindot_notifications
flutter pub publish --dry-run
```

`--dry-run` нічого не публікує, але показує:
- скільки балів дає твій пакет (pub.dev оцінює якість 0-160);
- усі warning-и, які треба виправити перед справжньою публікацією
  (відсутній LICENSE, неправильний pubspec, тощо);
- список файлів, які увійдуть у tarball.

Очікувані попередження для першої публікації:
- "Homepage / repository URLs do not point to a valid Git repository" — ОК,
  якщо ти ще не створив GitHub-репо. Можна або створити порожній публічний
  репо `pingwindot_notifications` на GitHub і вставити URL у pubspec.yaml,
  або тимчасово видалити поля `homepage` / `repository` з pubspec.

Якщо warning-и критичні — скажи, виправимо разом перед справжнім пушем.

## Крок 4. Опціонально: створити GitHub-репо

Pub.dev підвищує health score, якщо `homepage` / `repository` ведуть на
живий публічний репозиторій з тим самим кодом.

Опція А (рекомендую) — створити репо `pingwindot_notifications` на твоєму
GitHub-акаунті:

```bash
cd pingwindot_notifications
git init
git add .
git commit -m "Initial release 0.1.0"
gh repo create pingwindot_notifications --public --source=. --push
```

Після цього оновити `homepage` / `repository` у `pubspec.yaml` на твій
реальний GitHub URL і ще раз `flutter pub publish --dry-run`.

Опція Б — пропустити GitHub поки що, видалити поля `homepage` і
`repository` з `pubspec.yaml`. Можна додати назад при майбутніх версіях.

## Крок 5. Реальна публікація

Коли dry-run чистий:

```bash
flutter pub publish
```

CLI попросить:
1. Підтвердження умов публікації (друк `y`).
2. Можливу веб-авторизацію через браузер.

Після успіху побачиш URL виду `https://pub.dev/packages/pingwindot_notifications/versions/0.1.0`.

⚠️ **Версія публікується назавжди.** Pub.dev не дозволяє замінити вже
опубліковану версію. Якщо знайдеш баг — піднімай `version: 0.1.1` у
pubspec.yaml і публікуй наново.

## Крок 6. Перевірити, що пакет видно

```bash
# Спробуй pull у тестовому проєкті:
flutter pub add pingwindot_notifications
```

Або відкрий `https://pub.dev/packages/pingwindot_notifications` у браузері —
має показати README, версію, score.

## Крок 7. Додати в FlutterFlow

У FlutterFlow проєкті `ping-win-dot-ganftv`:

1. **Custom Code → Settings → Pub Dependencies → +** (точна назва пункту може
   варіюватись між версіями FF).
2. Назва: `pingwindot_notifications`, версія: `^0.1.0`.
3. Зберегти.

Або, якщо ти волієш керувати через FF AI DSL, у `dsl/edit.dart`:

```dart
app.pubDependency('pingwindot_notifications', '^0.1.0');
```

Після цього зробити `flutterflow ai run dsl/edit.dart --project-id ping-win-dot-ganftv --commit-message "Add pingwindot_notifications plugin"`.

## Що далі

Дивись `HOST_APP_INTEGRATION.md` поряд із цим файлом — там покроково що
зробити в самому додатку (Manifest meta-data, Dart custom action для
mirror-у сесії, edge-функція).

## Якщо щось пішло не так

| Ситуація                                              | Рішення                                                         |
| ----------------------------------------------------- | --------------------------------------------------------------- |
| Ім'я зайнято на pub.dev                                | Зміни `name:` у pubspec.yaml, скажи мені — оновлю всі посилання |
| `flutter pub publish` лається на line endings (CRLF)   | `git config --global core.autocrlf input` і перепакувати          |
| Health score < 100 через відсутній GitHub               | Створити публічний репо (Крок 4 опція А)                          |
| Помилка про `pluginClass not found`                    | Ймовірно зміна namespace — перевір `pubspec.yaml` і `.kt` файли  |
