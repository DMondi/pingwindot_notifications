# Інтеграція `pingwindot_notifications` у FlutterFlow проєкт

Покроковий чек-ліст для FlutterFlow проєкту `ping-win-dot-ganftv` після того,
як плагін `pingwindot_notifications` опубліковано на pub.dev.

> Виконуй пункти у вказаному порядку — кожен залежить від попередніх.

## Крок 1. Environment Values (виконано)

Створено в `Settings & Integrations → App Settings → Dev Environments`:
- `supabaseUrl` = повний URL Supabase проєкту
- `supabaseAnonKey` = anon public key з Supabase Dashboard → API

## Крок 2. Додати pub-залежність

`Custom Code → Settings → Pub Dependencies → +`:
- Назва: `pingwindot_notifications`
- Версія: `^0.1.0`
- Зберегти.

Або через FF AI DSL у `dsl/edit.dart`:
```dart
app.pubDependency('pingwindot_notifications', '^0.1.0');
```

## Крок 3. Manifest `<meta-data>` для конфігу Supabase

`Project Settings → Android → File Settings → AndroidManifest.xml →
App Component Tags → +`

Додати один запис із таким вмістом:

```xml
<meta-data
    android:name="dev.pingwindot.notifications.SUPABASE_URL"
    android:value="{{supabaseUrl}}" />
<meta-data
    android:name="dev.pingwindot.notifications.SUPABASE_ANON_KEY"
    android:value="{{supabaseAnonKey}}" />
```

Після збереження в FF перевір preview Manifest-у — `{{...}}` має замінитися
на реальні значення з Environment Values.

> **`<service>` і `<receiver>` додавати НЕ ТРЕБА** — вони вже всередині
> плагіна, Android Gradle Manifest Merger підтягне їх автоматично при білді.

## Крок 4. Custom Action `mirrorSupabaseSession`

`Custom Code → Custom Actions → +`:
- Name: `mirrorSupabaseSession`
- Arguments: немає
- Return Type: `Action` (Future без значення)
- Description: `Mirror Supabase session into native-readable SharedPreferences key for notification action button auth.`
- Code:

```dart
// Automatic FlutterFlow imports
import '/backend/schema/structs/index.dart';
import '/backend/supabase/supabase.dart';
import '/flutter_flow/flutter_flow_theme.dart';
import '/flutter_flow/flutter_flow_util.dart';
import 'index.dart';
import '/flutter_flow/custom_functions.dart';
import 'package:flutter/material.dart';
// Begin custom action code

import 'dart:async';
import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

const String _kSessionKey = 'pingwin.session';
StreamSubscription<AuthState>? _pingwinAuthSub;

Future mirrorSupabaseSession() async {
  final supabase = Supabase.instance.client;
  await _writeMirror(supabase.auth.currentSession);
  await _pingwinAuthSub?.cancel();
  _pingwinAuthSub = supabase.auth.onAuthStateChange.listen((data) async {
    await _writeMirror(data.session);
  });
}

Future<void> _writeMirror(Session? session) async {
  final prefs = await SharedPreferences.getInstance();
  if (session == null) {
    await prefs.remove(_kSessionKey);
    return;
  }
  final json = jsonEncode({
    'access_token': session.accessToken,
    'refresh_token': session.refreshToken ?? '',
    'expires_at': session.expiresAt ?? 0,
  });
  await prefs.setString(_kSessionKey, json);
}
```

## Крок 5. Викликати `mirrorSupabaseSession` при старті

Один виклик за процес додатку — підписка на `onAuthStateChange` живе до
кінця процесу. Найчистіший варіант: на on-load `HomePage` поряд із
`initializeAndroidFCM`:

```
HomePage → On Load → Add Action → Custom Action → mirrorSupabaseSession
```

Поставити після `initializeAndroidFCM` (порядок не критичний, але логічно
після ініціалізації Firebase).

Або через DSL:
```dart
app.editPageOnLoad('HomePage', [
  // існуючі on-load дії...
  CustomAction('mirrorSupabaseSession'),
]);
```

## Крок 6. Edge-функція — payload для Android

Поточний edge-функція шле гібридний `notification + data` payload. Для
Android-токенів треба перейти на **data-only**:

```ts
if (token.device_type === 'android') {
  message.android = { priority: 'HIGH' };
  message.data = {
    notification_id: notificationId,
    recipient_id: recipientId,
    title: title,
    body: body,
  };
  // НЕ додавати message.notification для Android!
} else {
  // iOS / Web — лишити як було
  message.notification = { title, body };
  message.data = { notification_id: notificationId, recipient_id: recipientId };
}
```

## Крок 7. POST_NOTIFICATIONS дозвіл (Android 13+)

Перевір, що в Manifest є:
```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

`firebase_messaging` плагін зазвичай це додає сам. Запит дозволу під час
runtime робить `initializeAndroidFCM` через `messaging.requestPermission()` —
це вже працює.

## Крок 8. Зібрати і протестувати

1. `flutterflow ai run dsl/edit.dart --project-id ping-win-dot-ganftv --commit-message "Add native FCM action button via pingwindot_notifications plugin"`
2. Зібрати APK через FF (Test Mode або Release).
3. Встановити на тестовий пристрій.
4. Залогінитись.
5. Послати тестовий push (через edge-функцію або curl).

### Очікуваний результат

- У шторці зʼявляється сповіщення з заголовком, текстом і кнопкою «+».
- Тап «+» → сповіщення показує «Sending acknowledgement…» з прогрес-баром.
- За 1-2 секунди → «Acknowledgement received ✓», автоматично закривається.
- У Supabase `notification_recipients` — рядок отримує статус так само, як
  при тапі «+» з UI додатку.

### Якщо щось не так

| Симптом                                                  | Причина і фікс                                                                                              |
| -------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| Кнопка «+» не зʼявляється                                  | Edge-функція досі шле блок `notification` для Android. Дивись Крок 6                                        |
| Тап «+» одразу відкриває додаток без проміжного стану       | `mirrorSupabaseSession` ще не викликалася (Крок 5) або користувач щойно залогінився і прев-ситуація з сесією |
| «Sending…» зависло і потім додаток відкрився               | Refresh не вдався (offline / refresh_token відкликано). Це штатний fallback                                  |
| Сповіщення дублюються                                    | Payload досі гібридний — Android рендерить системно ПЛЮС наш service ловить data. Крок 6                     |
| `adb logcat \| grep PingWinFcmService` — тиша              | Service не отримав push. Або payload не data-only, або плагін не доданий у залежності (Крок 2)              |

## Налагодження через adb

```bash
# Логи сервісу і receiver-а:
adb logcat | grep -E "PingWinFcmService|NotificationActionReceiver"

# Перевірити, що сесія в prefs (debug-build, root або run-as):
adb shell run-as ua.kyiv.dmon.pingwindot \
  cat /data/data/ua.kyiv.dmon.pingwindot/shared_prefs/FlutterSharedPreferences.xml \
  | grep -A1 pingwin.session
```

## План відкату

1. **Найшвидше:** edge-функція знову шле гібридний payload → плагін отримує
   нічого, Android рендерить системно як раніше. Кнопки немає, але і нічого
   не зламано.
2. У FF прибрати `mirrorSupabaseSession` з on-load → плагін не отримує
   сесії, на тап завжди відкриватиме UI. Поведінка ~ як до інтеграції плюс
   зайвий крок.
3. У Pub Dependencies прибрати `pingwindot_notifications`. Білд повертається
   до стану до інтеграції.

Кожен крок незалежний — обирай рівень відкату по серйозності проблеми.
