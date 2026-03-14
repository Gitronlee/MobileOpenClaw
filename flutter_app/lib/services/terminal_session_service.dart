import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:flutter_pty/flutter_pty.dart';
import 'preferences_service.dart';
import 'terminal_service.dart';
import 'native_bridge.dart';

/// Service that manages PTY session lifecycle independently of screen navigation.
/// This keeps the terminal session alive when the user navigates away and returns.
class TerminalSessionService {
  static final TerminalSessionService _instance =
      TerminalSessionService._internal();
  factory TerminalSessionService() => _instance;
  TerminalSessionService._internal();

  Pty? _pty;
  bool _isInitialized = false;
  int? _sessionPid;
  StreamController<String> _outputController =
      StreamController<String>.broadcast();
  StreamController<int?> _exitCodeController =
      StreamController<int?>.broadcast();
  String? _lastError;

  // Streams
  Stream<String> get outputStream => _outputController.stream;
  Stream<int?> get exitCodeStream => _exitCodeController.stream;

  // State
  bool get isRunning => _pty != null && _sessionPid != null;
  int? get sessionPid => _sessionPid;
  String? get lastError => _lastError;

  /// Initialize the terminal session. If a session was previously running,
  /// attempts to restore it. Otherwise creates a new session.
  Future<bool> init() async {
    final prefs = PreferencesService();
    await prefs.init();

    // Recreate controllers if they were disposed (allows multiple screen lifecycle)
    if (_outputController.isClosed) {
      _outputController = StreamController<String>.broadcast();
    }
    if (_exitCodeController.isClosed) {
      _exitCodeController = StreamController<int?>.broadcast();
    }

    // Try to restore previous session PID
    final savedPid = prefs.terminalSessionPid;
    if (savedPid != null) {
      // Check if process is still running
      try {
        Process.killPid(savedPid, ProcessSignal.sigterm);
        _sessionPid = savedPid;
        _lastError = 'Restored existing session (PID: $savedPid)';
      } on ProcessException catch (_) {
        // Process doesn't exist, need to create new session
        prefs.terminalSessionPid = null;
        _lastError = null;
      }
    }

    _isInitialized = true;
    return isRunning;
  }

  /// Start a new PTY session or restore an existing one.
  /// Returns true if session is ready.
  Future<bool> start() async {
    if (isRunning) return true;

    try {
      // Ensure directories exist
      try {
        await NativeBridge.setupDirs();
      } catch (_) {}
      try {
        await NativeBridge.writeResolv();
      } catch (_) {}

      final config = await TerminalService.getProotShellConfig();
      final args = TerminalService.buildProotArgs(config);

      _pty = Pty.start(
        config['executable']!,
        arguments: args,
        environment: TerminalService.buildHostEnv(config),
        columns: 80,
        rows: 24,
      );

      _sessionPid = _pty!.pid;

      // Save PID to preferences for restoration
      final prefs = PreferencesService();
      await prefs.init();
      prefs.terminalSessionPid = _sessionPid;

      // Listen to output
      _pty!.output.cast<List<int>>().listen((data) {
        final text = utf8.decode(data, allowMalformed: true);
        _outputController.add(text);
      });

      // Listen for exit
      _pty!.exitCode.then((code) {
        _exitCodeController.add(code);
        _sessionPid = null;
        _pty = null;
        prefs.terminalSessionPid = null;
      });

      _lastError = null;
      return true;
    } catch (e) {
      _lastError = 'Failed to start terminal: $e';
      return false;
    }
  }

  /// Write data to the terminal
  void write(String data) {
    if (_pty != null) {
      _pty!.write(Uint8List.fromList(utf8.encode(data)));
    }
  }

  /// Write binary data to the terminal
  void writeBytes(List<int> data) {
    if (_pty != null) {
      _pty!.write(Uint8List.fromList(data));
    }
  }

  /// Resize the terminal
  void resize(int columns, int rows) {
    if (_pty != null) {
      _pty!.resize(rows, columns);
    }
  }

  /// Kill the terminal session
  Future<void> kill() async {
    if (_pty != null) {
      _pty!.kill();
      _sessionPid = null;
      _pty = null;

      final prefs = PreferencesService();
      await prefs.init();
      prefs.terminalSessionPid = null;
    }
  }

  /// Check if the session is still running
  Future<bool> isSessionRunning() async {
    if (_sessionPid == null) return false;
    try {
      Process.killPid(_sessionPid!, ProcessSignal.sigterm);
      return true;
    } on ProcessException catch (_) {
      // Process no longer exists
      _sessionPid = null;
      _pty = null;
      return false;
    }
  }

  /// Clear the session (kill and remove from preferences)
  Future<void> clear() async {
    await kill();
    final prefs = PreferencesService();
    await prefs.init();
    prefs.terminalSessionPid = null;
    _lastError = null;
  }

  /// Dispose resources
  void dispose() {
    // Note: We intentionally do NOT kill the PTY here.
    // The session should persist across screen navigation.
    // Only kill() or clear() should explicitly terminate the session.
    _outputController.close();
    _exitCodeController.close();
  }
}