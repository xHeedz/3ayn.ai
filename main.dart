import 'package:flutter/material.dart';
import 'package:speech_to_text/speech_to_text.dart' as stt;
import 'app_state.dart';
import 'narrator.dart';
import 'screens.dart';

void main() => runApp(const ThreeAynApp());

class ThreeAynApp extends StatelessWidget {
  const ThreeAynApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '3ayn عين',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF0A7D6B),
          brightness: Brightness.dark,
        ),
        scaffoldBackgroundColor: const Color(0xFF07110F),
      ),
      home: const Root(),
    );
  }
}

class Root extends StatelessWidget {
  const Root({super.key});

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: AppState.instance,
      builder: (context, _) => AppState.instance.onboarded
          ? const MainShell()
          : const OnboardingScreen(),
    );
  }
}

class MainShell extends StatefulWidget {
  const MainShell({super.key});

  @override
  State<MainShell> createState() => _MainShellState();
}

class _MainShellState extends State<MainShell> {
  int _index = 0;

  final stt.SpeechToText _speech = stt.SpeechToText();
  bool _speechAvailable = false;
  bool _isListening = false;

  @override
  void initState() {
    super.initState();
    _initSpeech();
    final s = AppState.instance;
    Narrator.instance.setLang(s.lang).then((_) {
      Narrator.instance.say(
        s.isAr ? 'مرحباً ${s.name}. عين جاهزة.' : 'Hello ${s.name}. 3ayn is ready.',
      );
    });
  }

  Future<void> _initSpeech() async {
    // Passing onError/onStatus is what makes silent failures visible. If the
    // mic FAB "does nothing", the reason (e.g. error_permission,
    // error_speech_timeout, or a plugin init failure) shows up in the console.
    final available = await _speech.initialize(
      onError: (e) => debugPrint(
        'speech_to_text error: ${e.errorMsg} (permanent: ${e.permanent})',
      ),
      onStatus: (status) => debugPrint('speech_to_text status: $status'),
    );
    if (!mounted) return;
    setState(() => _speechAvailable = available);

    // Tell the user out loud if speech could not start — otherwise a missing
    // microphone permission just looks like a dead button.
    if (!available) {
      Narrator.instance.say(
        AppState.instance.isAr
            ? 'تعذّر تشغيل التعرّف على الصوت. تحقّق من إذن الميكروفون.'
            : 'Speech recognition is unavailable. Check the microphone permission.',
      );
    }
  }

  /// Picks a locale the device actually supports. If the exact preferred
  /// locale (e.g. ar_LB) isn't installed, fall back to any locale for the
  /// same language (ar_SA, en_GB…), and if none, return null so the platform
  /// uses its own default instead of failing to listen.
  Future<String?> _resolveLocale() async {
    final wantAr = AppState.instance.isAr;
    final preferred = wantAr ? 'ar_LB' : 'en_US';
    final prefix = wantAr ? 'ar' : 'en';

    final locales = await _speech.locales();
    final ids = locales.map((l) => l.localeId).toList();

    if (ids.contains(preferred)) return preferred;
    for (final id in ids) {
      if (id.toLowerCase().startsWith(prefix)) return id;
    }
    return null;
  }

  /// Normalises common Arabic letter variants (hamza forms, taa marbuta)
  /// so "أين", "اين" style spelling differences all match the same
  /// keyword list below.
  String _normalize(String input) {
    return input
        .toLowerCase()
        .trim()
        .replaceAll('أ', 'ا')
        .replaceAll('إ', 'ا')
        .replaceAll('آ', 'ا')
        .replaceAll('ة', 'ه');
  }

  void _handleVoiceCommand(String words) {
    final command = _normalize(words);

    bool containsAny(List<String> phrases) =>
        phrases.any((phrase) => command.contains(phrase));

    void openPage(int page) {
      if (!mounted) return;
      setState(() => _index = page);
    }

    void runAction(int page, VoiceCommand voiceCommand) {
      if (!mounted) return;
      setState(() => _index = page);
      // Wait a frame so the target screen has mounted and attached its
      // VoiceCommandBus listener before we send the command.
      WidgetsBinding.instance.addPostFrameCallback((_) {
        VoiceCommandBus.send(voiceCommand);
      });
    }

    // Describe surroundings
    if (containsAny([
      'describe my surroundings',
      'describe the surroundings',
      'describe what is around me',
      'what is around me',
      'whats around me',
      'صف ما حولي',
      'اوصف ما حولي',
      'ماذا حولي',
      'شو حولي',
    ])) {
      runAction(1, const VoiceCommand(VoiceActionType.describeScene));
      return;
    }

    // Identify person
    if (containsAny([
      'who is in front of me',
      'who is this',
      'who is ahead of me',
      'من امامي',
      'مين امامي',
      'مين قدامي',
      'من هذا',
      'مين هيدا',
    ])) {
      runAction(1, const VoiceCommand(VoiceActionType.identifyPerson));
      return;
    }

    // Read text
    if (containsAny([
      'read the text in front of me',
      'read the text',
      'read this text',
      'read this',
      'اقرا لي النص امامي',
      'اقرا النص امامي',
      'اقرا لي النص',
      'اقرا النص',
      'اقرا هذا',
    ])) {
      runAction(3, const VoiceCommand(VoiceActionType.readText));
      return;
    }

    // Find object
    final findRequested = containsAny([
      'find',
      'where is',
      'where are',
      'locate',
      'دلني',
      'ابحث',
      'اين',
      'وين',
    ]);

    String? objectName;
    if (containsAny(['bottle', 'water bottle', 'قنينه', 'زجاجه'])) {
      objectName = 'Bottle';
    } else if (containsAny(
        ['phone', 'mobile', 'cell phone', 'هاتف', 'موبايل', 'تلفون', 'تليفون'])) {
      objectName = 'Phone';
    } else if (containsAny(['cup', 'mug', 'كوب', 'فنجان'])) {
      objectName = 'Cup';
    } else if (containsAny(['chair', 'كرسي'])) {
      objectName = 'Chair';
    } else if (containsAny(['door', 'باب'])) {
      objectName = 'Door';
    } else if (containsAny(
        ['bag', 'backpack', 'handbag', 'حقيبه', 'شنطه', 'شنته'])) {
      objectName = 'Bag';
    }

    if (findRequested && objectName != null) {
      runAction(
        2,
        VoiceCommand(VoiceActionType.findObject, objectName: objectName),
      );
      return;
    }

    // Plain page navigation (checked after the specific intents above so
    // e.g. "find my phone" is never swallowed by the generic "find" nav).
    if (containsAny(['home', 'الرئيسيه'])) {
      openPage(0);
      return;
    }
    if (containsAny(['ask', 'اسال'])) {
      openPage(1);
      return;
    }
    if (containsAny(['find', 'دلني', 'ابحث'])) {
      openPage(2);
      return;
    }
    if (containsAny(['read', 'اقرا'])) {
      openPage(3);
      return;
    }
    if (containsAny(['settings', 'الاعدادات'])) {
      openPage(4);
      return;
    }

    Narrator.instance.say(
      AppState.instance.isAr
          ? 'لم افهم الامر. حاول مره اخرى.'
          : 'I did not understand the command. Please try again.',
    );
  }

  Future<void> _toggleListening() async {
    if (!_speechAvailable) {
      await _initSpeech();
      if (!_speechAvailable) return;
    }

    if (_isListening) {
      await _speech.stop();
      if (!mounted) return;
      setState(() => _isListening = false);
    } else {
      final localeId = await _resolveLocale();
      await _speech.listen(
        localeId: localeId, // null => platform default if ar_LB isn't installed
        listenFor: const Duration(seconds: 8),
        pauseFor: const Duration(seconds: 3),
        onResult: (result) {
          if (result.finalResult) {
            _handleVoiceCommand(result.recognizedWords);
            if (mounted) setState(() => _isListening = false);
          }
        },
      );
      if (!mounted) return;
      setState(() => _isListening = true);
    }
  }

  @override
  void dispose() {
    _speech.stop();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: AppState.instance,
      builder: (context, _) {
        final ar = AppState.instance.isAr;
        const pages = [
          HomeScreen(),
          AskScreen(),
          FindScreen(),
          ReadScreen(),
          SettingsScreen(),
        ];
        return Scaffold(
          body: SafeArea(child: pages[_index]),
          floatingActionButton: FloatingActionButton(
            onPressed: _toggleListening,
            tooltip: ar ? 'الأوامر الصوتية' : 'Voice commands',
            child: Icon(_isListening ? Icons.stop : Icons.mic),
          ),
          bottomNavigationBar: NavigationBar(
            selectedIndex: _index,
            onDestinationSelected: (i) => setState(() => _index = i),
            destinations: [
              NavigationDestination(
                icon: const Icon(Icons.home_outlined),
                selectedIcon: const Icon(Icons.home),
                label: ar ? 'الرئيسية' : 'Home',
              ),
              NavigationDestination(
                icon: const Icon(Icons.mic_none),
                selectedIcon: const Icon(Icons.mic),
                label: ar ? 'اسأل' : 'Ask',
              ),
              NavigationDestination(
                icon: const Icon(Icons.search),
                label: ar ? 'دلّني' : 'Find',
              ),
              NavigationDestination(
                icon: const Icon(Icons.menu_book_outlined),
                selectedIcon: const Icon(Icons.menu_book),
                label: ar ? 'اقرأ' : 'Read',
              ),
              NavigationDestination(
                icon: const Icon(Icons.settings_outlined),
                selectedIcon: const Icon(Icons.settings),
                label: ar ? 'الإعدادات' : 'Settings',
              ),
            ],
          ),
        );
      },
    );
  }
}
