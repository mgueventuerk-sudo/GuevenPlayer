# LibVLC greift per JNI auf diese Klassen zu.
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }

# Enum-Namen werden in DataStore und Sicherungen als Text gespeichert.
-keepclassmembers enum com.mg.player.** { *; }

# OkHttp: optionale TLS-Anbieter, die auf Android nicht vorhanden sind.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Release-Versionen schreiben nichts ins System-Protokoll (Logcat): Adressen, Fehlertexte und Gerätedaten bleiben
# auf dem Gerät. Das eigene Fehlerprotokoll (AppLog, Datei in der App) bleibt unverändert.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

# WireGuard: Die native Bibliothek (libwg-go) findet ihre Gegenstücke über Klassen- und Methodennamen.
-keep class com.wireguard.android.backend.GoBackend { native <methods>; }
-keep class com.wireguard.android.backend.GoBackend$VpnService { *; }
