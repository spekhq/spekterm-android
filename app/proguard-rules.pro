# The app's own R8 rules. The libraries ship their consumer rules, and kotlinx.serialization's bundled rules keep the
# generated serializers (`play-release` D3). Add a rule only for a need seen in the shrunk build, naming it.

# The connection log names exceptions by class (`Throwable.logName()` in log/ConnectionLog.kt); keep those names, so a
# shrunk build logs `SSLHandshakeException` rather than an obfuscated one. Names only: the classes are still shrunk.
-keepnames class * extends java.lang.Throwable
