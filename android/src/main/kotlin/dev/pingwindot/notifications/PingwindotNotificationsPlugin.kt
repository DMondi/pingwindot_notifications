package dev.pingwindot.notifications

import io.flutter.embedding.engine.plugins.FlutterPlugin

/**
 * No-op Flutter plugin entry point.
 *
 * This plugin contributes only Android Manifest entries and native Kotlin
 * classes (FCM service + broadcast receiver). It does not expose any Dart
 * MethodChannel, but Flutter's plugin discovery still requires a class that
 * implements [FlutterPlugin].
 */
class PingwindotNotificationsPlugin : FlutterPlugin {
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // Nothing to register — manifest merger handles the wiring.
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // Nothing to clean up.
    }
}
