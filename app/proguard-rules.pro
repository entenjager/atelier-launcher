
-dontwarn android.hardware.fingerprint.**

# launcher: rhino-regeln (vom Installationsskript eingetragen)
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn java.beans.**
-dontwarn javax.lang.model.**

# launcher: r8-regeln (eigener Code bleibt komplett, optimiert werden die Bibliotheken wie Compose)
-keep class de.atelierlauncher.app.** { *; }
-ignorewarnings
