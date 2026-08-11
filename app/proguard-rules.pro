# Keep BouncyCastle providers reachable via reflection.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
