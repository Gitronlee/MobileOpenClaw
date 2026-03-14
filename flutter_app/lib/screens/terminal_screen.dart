import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:xterm/xterm.dart';
import 'package:url_launcher/url_launcher.dart';
import '../services/native_bridge.dart';
import '../services/screenshot_service.dart';
import '../services/terminal_session_service.dart';
import '../widgets/terminal_toolbar.dart';

class TerminalScreen extends StatefulWidget {
  const TerminalScreen({super.key});

  @override
  State<TerminalScreen> createState() => _TerminalScreenState();
}

class _TerminalScreenState extends State<TerminalScreen> {
  late final Terminal _terminal;
  late final TerminalController _controller;
  final _sessionService = TerminalSessionService();
  StreamSubscription? _outputSubscription;
  StreamSubscription? _exitSubscription;
  bool _loading = true;
  String? _error;
  final _ctrlNotifier = ValueNotifier<bool>(false);
  final _altNotifier = ValueNotifier<bool>(false);
  final _screenshotKey = GlobalKey();
  static final _anyUrlRegex = RegExp(r'https?://[^\s<>\[\]"' "'" r'\)]+');
  /// Box-drawing and other TUI characters that break URLs when copied
  static final _boxDrawing = RegExp(r'[│┤├┬┴┼╮╯╰╭─╌╴╶┌┐└┘◇◆]+');

  static const _fontFallback = [
    'monospace',
    'Noto Sans Mono',
    'Noto Sans Mono CJK SC',
    'Noto Sans Mono CJK TC',
    'Noto Sans Mono CJK JP',
    'Noto Color Emoji',
    'Noto Sans Symbols',
    'Noto Sans Symbols 2',
    'sans-serif',
  ];

  @override
  void initState() {
    super.initState();
    _terminal = Terminal(maxLines: 10000);
    _controller = TerminalController();
    _startSession();
  }

  Future<void> _startSession() async {
    // Initialize and start the session
    await _sessionService.init();
    await _sessionService.start();

    // Subscribe to output stream
    _outputSubscription = _sessionService.outputStream.listen((text) {
      if (mounted) {
        _terminal.write(text);
      }
    });

    // Subscribe to exit events
    _exitSubscription = _sessionService.exitCodeStream.listen((code) {
      if (mounted) {
        _terminal.write('\r\n[Process exited with code ${code ?? 0}]\r\n');
      }
    });

    if (mounted) {
      setState(() {
        _loading = false;
        if (_sessionService.lastError != null) {
          _error = _sessionService.lastError;
        }
      });
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Update terminal size when screen size changes
    if (!_loading && _error == null) {
      final columns = _terminal.viewWidth;
      final rows = _terminal.viewHeight;
      if (columns > 0 && rows > 0) {
        _sessionService.resize(columns, rows);
      }
    }
  }

  String? _getSelectedText() {
    final selection = _controller.selection;
    if (selection == null || selection.isCollapsed) return null;

    final range = selection.normalized;
    final sb = StringBuffer();
    for (int y = range.begin.y; y <= range.end.y; y++) {
      if (y >= _terminal.buffer.lines.length) break;
      final line = _terminal.buffer.lines[y];
      final from = (y == range.begin.y) ? range.begin.x : 0;
      final to = (y == range.end.y) ? range.end.x : null;
      sb.write(line.getText(from, to));
      if (y < range.end.y) sb.writeln();
    }
    final text = sb.toString().trim();
    return text.isEmpty ? null : text;
  }

  /// Extract a clean URL from selected text by stripping box-drawing
  /// chars and rejoining lines, but splitting on `http` boundaries
  /// so concatenated URLs don't merge into one.
  String? _extractUrl(String text) {
    final clean = text.replaceAll(_boxDrawing, '').replaceAll(RegExp(r'\s+'), '');
    // Split before each http(s):// so concatenated URLs become separate
    final parts = clean.split(RegExp(r'(?=https?://)'));
    // Return the longest URL match (token URLs are longest)
    String? best;
    for (final part in parts) {
      final match = _anyUrlRegex.firstMatch(part);
      if (match != null) {
        final url = match.group(0)!;
        if (best == null || url.length > best.length) {
          best = url;
        }
      }
    }
    return best;
  }

  void _copySelection() {
    final text = _getSelectedText();
    if (text == null) return;

    Clipboard.setData(ClipboardData(text: text));

    // If the copied text contains a URL, offer "Open" action
    final url = _extractUrl(text);
    if (url != null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: const Text('Copied to clipboard'),
          duration: const Duration(seconds: 3),
          action: SnackBarAction(
            label: 'Open',
            onPressed: () {
              final uri = Uri.tryParse(url);
              if (uri != null) {
                launchUrl(uri, mode: LaunchMode.externalApplication);
              }
            },
          ),
        ),
      );
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Copied to clipboard'),
          duration: Duration(seconds: 1),
        ),
      );
    }
  }

  void _openSelection() {
    final text = _getSelectedText();
    if (text == null) return;

    final url = _extractUrl(text);
    if (url != null) {
      final uri = Uri.tryParse(url);
      if (uri != null) {
        launchUrl(uri, mode: LaunchMode.externalApplication);
        return;
      }
    }
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('No URL found in selection'),
        duration: Duration(seconds: 1),
      ),
    );
  }

  Future<void> _paste() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    if (data?.text != null && data!.text!.isNotEmpty) {
      _sessionService.write(data.text!);
    }
  }

  Future<void> _takeScreenshot() async {
    final path = await ScreenshotService.capture(_screenshotKey);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(path != null
            ? 'Screenshot saved: ${path.split('/').last}'
            : 'Failed to capture screenshot'),
      ),
    );
  }

  /// Detect URLs in terminal at tap position. Joins adjacent lines
  /// and strips box-drawing chars to handle wrapped URLs.
  void _handleTap(TapUpDetails details, CellOffset offset) {
    final totalLines = _terminal.buffer.lines.length;
    final startRow = (offset.y - 2).clamp(0, totalLines - 1);
    final endRow = (offset.y + 2).clamp(0, totalLines - 1);

    final sb = StringBuffer();
    for (int row = startRow; row <= endRow; row++) {
      sb.write(_getLineText(row).trimRight());
    }
    final url = _extractUrl(sb.toString());
    if (url != null) {
      _openUrl(url);
    }
  }

  String _getLineText(int row) {
    try {
      final line = _terminal.buffer.lines[row];
      final sb = StringBuffer();
      for (int i = 0; i < line.length; i++) {
        final char = line.getCodePoint(i);
        if (char != 0) {
          sb.writeCharCode(char);
        }
      }
      return sb.toString();
    } catch (_) {
      return '';
    }
  }

  Future<void> _openUrl(String url) async {
    final uri = Uri.tryParse(url);
    if (uri == null) return;

    final shouldOpen = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Open Link'),
        content: Text(url),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () {
              Clipboard.setData(ClipboardData(text: url));
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(
                  content: Text('Link copied'),
                  duration: Duration(seconds: 1),
                ),
              );
              Navigator.pop(ctx, false);
            },
            child: const Text('Copy'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Open'),
          ),
        ],
      ),
    );

    if (shouldOpen == true) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }

  void _restartSession() async {
    setState(() {
      _loading = true;
      _error = null;
    });

    _outputSubscription?.cancel();
    _exitSubscription?.cancel();

    await _sessionService.clear();
    await _startSession();
  }

  @override
  void dispose() {
    _outputSubscription?.cancel();
    _exitSubscription?.cancel();
    _ctrlNotifier.dispose();
    _altNotifier.dispose();
    _controller.dispose();
    // Note: We do NOT kill the PTY here to preserve the session
    // when the user navigates away from this screen
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Terminal'),
        actions: [
          IconButton(
            icon: const Icon(Icons.camera_alt_outlined),
            tooltip: 'Screenshot',
            onPressed: _takeScreenshot,
          ),
          IconButton(
            icon: const Icon(Icons.copy),
            tooltip: 'Copy',
            onPressed: _copySelection,
          ),
          IconButton(
            icon: const Icon(Icons.open_in_browser),
            tooltip: 'Open URL',
            onPressed: _openSelection,
          ),
          IconButton(
            icon: const Icon(Icons.paste),
            tooltip: 'Paste',
            onPressed: _paste,
          ),
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Restart',
            onPressed: _restartSession,
          ),
        ],
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_loading) {
      return const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('Starting terminal...'),
          ],
        ),
      );
    }

    if (_error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                Icons.error_outline,
                size: 48,
                color: Theme.of(context).colorScheme.error,
              ),
              const SizedBox(height: 16),
              Text(
                _error!,
                textAlign: TextAlign.center,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
              const SizedBox(height: 16),
              FilledButton.icon(
                onPressed: _restartSession,
                icon: const Icon(Icons.refresh),
                label: const Text('Retry'),
              ),
            ],
          ),
        ),
      );
    }

    return Column(
      children: [
        Expanded(
          child: RepaintBoundary(
            key: _screenshotKey,
            child: TerminalView(
              _terminal,
              controller: _controller,
              textStyle: const TerminalStyle(
                fontSize: 11,
                height: 1.0,
                fontFamily: 'DejaVuSansMono',
                fontFamilyFallback: _fontFallback,
              ),
              onTapUp: _handleTap,
              onOutput: _handleTerminalOutput,
              onResize: _handleResize,
            ),
          ),
        ),
        TerminalToolbar(
          sessionService: _sessionService,
          ctrlNotifier: _ctrlNotifier,
          altNotifier: _altNotifier,
        ),
      ],
    );
  }

  void _handleTerminalOutput(String data) {
    // Intercept keyboard input when CTRL/ALT toolbar modifiers are active
    if (_ctrlNotifier.value && data.length == 1) {
      final code = data.toLowerCase().codeUnitAt(0);
      if (code >= 97 && code <= 122) {
        // Ctrl+a-z → bytes 1-26
        _sessionService.writeBytes(Uint8List.fromList([code - 96]));
        _ctrlNotifier.value = false;
        return;
      }
    }
    if (_altNotifier.value && data.isNotEmpty) {
      // Alt+key → ESC + key
      _sessionService.writeBytes(utf8.encode('\x1b$data'));
      _altNotifier.value = false;
      return;
    }
    _sessionService.write(data);
  }

  void _handleResize(int width, int height) {
    _sessionService.resize(width, height);
  }
}