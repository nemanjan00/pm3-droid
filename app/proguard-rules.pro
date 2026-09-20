# R8 rules for the release build.
#
# Deliberately small. The one reflective dependency here is
# usb-serial-for-android, which instantiates drivers via
# Class.getConstructor(UsbDevice.class).newInstance(...) -- but the AAR ships
# its own consumer rule keeping com.hoho.android.usbserial.driver.*, so
# nothing is needed for it here. Verified against the 3.8.1 AAR's proguard.txt.
#
# Everything else in this app is reached statically:
#  - Pm3Application / MainActivity / BridgeService are named in the manifest,
#    which AGP keeps automatically.
#  - The Lua RPC shim runs inside the native client, not on the JVM, so no
#    Kotlin class is looked up by name across that boundary.

# The client's own output is parsed, never its classes -- but keep the line
# numbers so a crash report from a release build is readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
