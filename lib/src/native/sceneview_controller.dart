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

  Future<Map?> markRequest() {
    if (!isRegistered) return Future.value(null);

    return _channel!.invokeMethod<Map>("markRequest");
  }

  void markResponse(double x, double y) {
    if (!isRegistered) return;

    _channel?.invokeMethod("markNow", {"x": x, "y": y});
  }

  // Add new methods here and call the method channel to execute them native side
}
