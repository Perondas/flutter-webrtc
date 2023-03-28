import 'dart:typed_data';

import 'package:flutter/services.dart';

class SceneViewController {
  MethodChannel? _channel;

  bool get isRegistered => _channel != null;

  void onViewRegistered(int id) {
    _channel = MethodChannel('scene_view_$id');
  }

  void displayDemo() {
    if (!isRegistered) return;

    _channel?.invokeMethod("showDemo");
  }

  Future<Uint8List?> markRequest() {
    if (!isRegistered) return Future.value(null);

    return _channel!.invokeMethod<Uint8List>("markRequest");
  }

  void markResponse(int x, int y) {
    if (!isRegistered) return;

    _channel?.invokeMethod("markResponse", {"x": x, "y": y});
  }

  // Add new methods here and call the method channel to execute them native side
}
