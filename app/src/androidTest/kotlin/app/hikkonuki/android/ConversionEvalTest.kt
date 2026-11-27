package app.hikkonuki.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hikkonuki.android.core.CameraDetector
import app.hikkonuki.android.core.SlimPlanner
import app.hikkonuki.android.core.VideoPlan
import app.hikkonuki.android.platform.AndroidSource
import app.hikkonuki.android.platform.AndroidVideoProber
import app.hikkonuki.android.platform.DirectVideoTranscoder
import app.hikkonuki.android.platform.Media3VideoTranscoder
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Converts real camera recordings on this device so the result can be judged off-device
 * (SSIM against the source, bit depth, colour levels, speed).
 *
 *     adb push DJI_xxx.MP4 /sdcard/Android/data/app.hikkonuki.android/files/eval_in/
 *     adb shell am instrument -w -e class app.hikkonuki.android.ConversionEvalTest -e evaluate true \
 *       app.hikkonuki.android.test/androidx.test.runner.AndroidJUnitRunner
 *     adb pull /sdcard/Android/data/app.hikkonuki.android/files/eval_out
 *
 * Each converter is run separately, not as a fallback chain, so their outputs can be
 * compared. Nothing is written to the gallery.
 */
@RunWith(AndroidJUnit4::class)
class ConversionEvalTest {
    @Test
    fun convertEverythingInEvalIn(): Unit = runBlocking {
        assumeTrue("pass -e evaluate true", InstrumentationRegistry.getArguments().getString("evaluate") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.getExternalFilesDir(null), "eval_in")
        val output = File(context.getExternalFilesDir(null), "eval_out").apply { deleteRecursively(); mkdirs() }
        val report = File(output, "report.txt")
        val source = AndroidSource(context)
        val converters = listOf(DirectVideoTranscoder(source, tenBit = true), DirectVideoTranscoder(source, tenBit = false), Media3VideoTranscoder(context, source))
        fun note(line: String) {
            Log.i("HikkonukiEval", line)
            report.appendText(line + "\n")
        }
        note("device=${android.os.Build.MODEL} soc=${android.os.Build.SOC_MODEL} android=${android.os.Build.VERSION.RELEASE}")
        for (file in input.listFiles().orEmpty().sortedBy { it.name }) {
            val camera = CameraDetector.fromFileName(file.name)
            val probed = AndroidVideoProber(source).probe(file.absolutePath, camera)
            val plan = SlimPlanner.plan(camera, probed.info)
            note("FILE ${file.name} bytes=${file.length()} camera=$camera info=${probed.info} model=${probed.model} plan=$plan")
            val spec = (plan as? VideoPlan.Transcode)?.spec ?: continue
            for (converter in converters) {
                val target = File(output, "${file.nameWithoutExtension}__${converter.name}.mp4")
                val started = System.nanoTime()
                val result = runCatching { converter.transcode(file.absolutePath, spec, target) {} }
                val seconds = (System.nanoTime() - started) / 1e9
                note(
                    "  ${converter.name}: " + result.fold(
                        { "OK \"$it\" bytes=${target.length()} seconds=%.1f speed=%.2fx".format(seconds, spec.durationMillis / 1000.0 / seconds) },
                        { "FAILED ${it.javaClass.simpleName}: ${it.message?.take(300)}" },
                    ),
                )
            }
        }
    }
}
