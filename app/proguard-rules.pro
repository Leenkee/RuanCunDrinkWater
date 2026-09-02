# Gson only reflects over its own JSON tree classes; app models are parsed explicitly.
-dontwarn org.codehaus.mojo.animal_sniffer.**

# OkHttp probes these optional desktop TLS providers; Android does not ship them.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
