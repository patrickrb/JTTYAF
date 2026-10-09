package radio.ks3ckc.jttyaf

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Guard test for the release R8 configuration.
 *
 * Enabling minification on the release build is only safe because
 * proguard-rules.pro keeps the names the native libjttyaf.so (and a couple of
 * reflection sites) look up by string. If someone deletes minifyEnabled or one
 * of those keep rules, the app still *builds* — it just silently breaks decode
 * (JttyCodec.DecodedFrame fields obfuscated), USB audio capture (callback
 * methods renamed) or USB serial probing (driver ctor/getSupportedDevices
 * stripped). None of that is caught by a normal unit test, so assert the
 * configuration itself stays intact.
 *
 * Unit tests run with the working directory at the module root (jttyaf/app), so
 * both files resolve relative to it.
 */
class R8KeepRulesTest {

    // Strip comments at load time so a directive/rule that's been commented out
    // — whole-line OR trailing-inline — can't satisfy a .contains() assertion.
    // proguard-rules.pro uses '#' comments; build.gradle uses '//'.
    private val proguardRules: String by lazy { stripComments("proguard-rules.pro", "#") }
    private val buildGradle: String by lazy { stripComments("build.gradle", "//") }

    private fun stripComments(fileName: String, marker: String): String =
        File(fileName).readLines()
            .joinToString("\n") { line ->
                val i = line.indexOf(marker)
                if (i >= 0) line.substring(0, i) else line
            }

    @Test
    fun releaseBuildEnablesR8() {
        assertThat(buildGradle).contains("minifyEnabled true")
    }

    @Test
    fun keepsDecodedFrameFieldsForJniFieldAccess() {
        assertThat(proguardRules)
            .contains("class radio.ks3ckc.jttyaf.jtty.JttyCodec\$DecodedFrame")
        assertThat(proguardRules).contains("<fields>;")
    }

    @Test
    fun keepsNativeCodecClass() {
        assertThat(proguardRules).contains("-keep class radio.ks3ckc.jttyaf.jtty.NativeJttyCodec { *; }")
    }

    @Test
    fun keepsUsbAudioCallbackMethodsForJni() {
        assertThat(proguardRules)
            .contains("com.k1af.ft8af.wave.UsbAudioNative\$AudioInputCallback")
        assertThat(proguardRules).contains("onAudioData(float[], int)")
        assertThat(proguardRules).contains("onCaptureStopped(int)")
    }

    @Test
    fun keepsReflectivelyInstantiatedUsbSerialDrivers() {
        assertThat(proguardRules)
            .contains("implements com.k1af.ft8af.serialport.UsbSerialDriver")
        assertThat(proguardRules).contains("<init>(android.hardware.usb.UsbDevice)")
        assertThat(proguardRules).contains("getSupportedDevices()")
    }

    @Test
    fun keepsLineNumbersSoMappingDeobfuscatesStackTraces() {
        assertThat(proguardRules).contains("-keepattributes SourceFile,LineNumberTable")
    }
}
