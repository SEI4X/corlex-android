# Crash reports and readable stacks

A normal app_open event proves event delivery, not complete crash symbolication. A native fatal report is normally delivered on the next launch with its original timestamp and release metadata. Do not deliberately crash a user application just to complete onboarding.

iOS release dSYMs require the matching symbol table uploader; Android minified releases require the matching R8 mapping. Keep the upload token in CI or an ignored build-only file. It must never enter Info.plist, BuildConfig, dart-defines, JS or app resources. A missing token/network leaves symbols pending rather than claiming success. Flutter obfuscated Dart AOT frames require separate split-debug-info handling; native dSYM/R8 mapping does not decode them.

Crash handlers are best effort; corrupted stacks may yield fewer frames. Existing reporters must be chained. See the release audit and actual native regression fixtures before enabling a candidate in production.
