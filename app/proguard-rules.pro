# The app's own R8 rules. The libraries ship their consumer rules, and kotlinx.serialization's bundled rules keep the
# generated serializers (`play-release` D3). Add a rule only for a need seen in the shrunk build, naming it.

# The connection log names exceptions by class (`Throwable.logName()` in log/ConnectionLog.kt); keep those names, so a
# shrunk build logs `SSLHandshakeException` rather than an obfuscated one. Names only: the classes are still shrunk.
-keepnames class * extends java.lang.Throwable

# The relay tunnel socket hides libcore's hidden `Socket.getFileDescriptor$()`, so Conscrypt wraps it with its engine
# socket rather than a descriptor one (`phone-relay` D1). Nothing in the app calls it and android.jar does not declare
# it, so R8 would remove it as unused; keep it.
-keepclassmembers class com.spekterm.android.net.TunnelSocket {
    java.io.FileDescriptor getFileDescriptor$();
}
