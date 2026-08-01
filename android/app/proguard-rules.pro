# Room generates the database implementation reflectively at startup.
-keep class androidx.room.RoomDatabase { *; }

# Tink, which backs EncryptedSharedPreferences, is annotated with Error Prone and
# JSR-305 annotations that exist only at compile time. They are never loaded at
# runtime, so R8 can safely ignore the dangling references.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# jasync + Netty back the Supabase sync. Netty probes for optional native and
# platform pieces by name and swallows the failures, so R8 only needs to be told
# the missing references are expected; the classes that are reached reflectively
# are its channel implementations.
# Netty resolves a handler's message type from its generic signature at runtime
# (`TypeParameterMatcher.find`), and R8 drops those attributes by default. Without
# them every pipeline handler fails to initialise with "cannot determine the type
# of the type parameter 'I'".
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

-dontwarn io.netty.**
-dontwarn com.github.jasync.**
-dontwarn org.slf4j.**
-dontwarn reactor.blockhound.**
-dontwarn com.oracle.svm.**
-dontwarn org.apache.logging.log4j.**
-keep class io.netty.channel.socket.nio.** { *; }
-keep class io.netty.channel.nio.** { *; }
-keep class io.netty.util.internal.** { *; }
-keep class io.netty.handler.ssl.** { *; }
# Netty's leak detector registers exclusions by *method name*
# (`ResourceLeakDetector.addExclusions(AbstractByteBufAllocator.class, "toLeakAwareBuffer")`)
# and throws from a static initialiser when the name is gone. Renaming anything in
# the buffer package therefore breaks every connection in a minified build, with a
# message that names nothing recognisable.
-keepclassmembers class io.netty.buffer.** { *; }
# Keeping the Signature attribute is not enough on its own: R8 also merges and
# inlines classes, and a handler whose generic superclass has been merged away has
# no type parameter left to read. The driver is small, so it is kept whole rather
# than chased class by class.
-keep class com.github.jasync.** { *; }
-keep class io.netty.channel.ChannelHandler { *; }
-keep class * extends io.netty.channel.ChannelHandlerAdapter { *; }
-keep class * extends io.netty.handler.codec.ByteToMessageDecoder { *; }
-keep class * extends io.netty.handler.codec.MessageToMessageEncoder { *; }
-keepclassmembers class io.netty.util.ReferenceCountUtil { *; }
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn org.joda.convert.**
# Android has no javax.security.sasl. Only SCRAM's *failure* path touches it, which
# is why a wrong password has to be caught as a Throwable, not an Exception - see
# PostgresCloud.connected.
-dontwarn javax.security.sasl.**
