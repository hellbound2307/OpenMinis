package com.openminis.app.tools.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * [T-android-ocr-tool] Local OCR over any image in the session.
 *
 * ML Kit text recognition v2 (bundled latin model — ships inside the APK,
 * fully offline, no Play Services dependency at runtime). Recognizes printed
 * latin text in photos, screenshots, scanned documents, whiteboards.
 *
 * Path semantics match [com.openminis.app.tools.ReadImageTool]: sandbox paths
 * (/var/minis/...) and minis:// URLs resolve against the CURRENT session's
 * host directories via [PRootKernel.resolveSessionHostPath].
 *
 * Output: recognized text blocks in reading order, with per-block bounding
 * confidence preserved implicitly by line grouping. Empty result is a success
 * with a hint (likely handwriting / non-latin / low light).
 */
object OcrTool {

    const val NAME = "ocr_read"
    private const val MAX_DIM = 2048

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Extract text from an image using on-device OCR (works " +
            "offline, no vision model needed). Recognizes printed latin-script " +
            "text in photos, screenshots, receipts, documents and whiteboards. " +
            "Supports the same paths as read_image (sandbox paths and minis:// " +
            "URLs). Use this instead of read_image when you only need the TEXT " +
            "from an image — it is fast and free. For charts, photos of scenes, " +
            "or anything needing visual understanding, use read_image instead.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Extract receipt total', 'Read whiteboard notes'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Linux path (e.g. /var/minis/attachments/photo.jpg) or minis:// URL (e.g. minis://attachments/photo.jpg) of the image to OCR."),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path"),
    )

    suspend fun execute(argsJson: String, sessionId: String?, context: Context?): ToolExecutionResult {
        val safeContext = context ?: return ToolExecutionResult("Internal error: no context", false)
        return try {
            val args = JSONObject(argsJson)
            val rawPath = args.optString("path", "")
            if (rawPath.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false)
            }
            val path = if (rawPath.startsWith("minis://")) {
                "/var/minis/" + java.net.URLDecoder.decode(rawPath.removePrefix("minis://"), "UTF-8")
            } else rawPath

            val file = (
                if (sessionId != null) {
                    PRootKernel.resolveSessionHostPath(sessionId, path, safeContext)
                } else null
                ) ?: PRootKernel.resolveHostPath(path)
            if (file == null || !file.exists()) {
                return ToolExecutionResult("File not found: $path", false)
            }

            val bitmap = decodeScaled(file)
                ?: return ToolExecutionResult(
                    "Could not decode image (unsupported format or corrupt file): $path",
                    false,
                )

            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            // suspendCancellableCoroutine requires a suspend caller — execute()
            // is suspend (dispatched from ChatViewModel's suspend executeTool).
            val text = suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it.text) }
                    .addOnFailureListener { cont.resume(null) }
            }
            recognizer.close()

            if (text.isNullOrBlank()) {
                ToolExecutionResult(
                    "No text recognized. The image may contain handwriting, " +
                        "non-latin script, or be too dark/blurry. Try read_image " +
                        "with a vision model instead.",
                    true,
                )
            } else {
                val trimmed = text.trim()
                val out = if (trimmed.length > 20_000) trimmed.take(20_000) + "\n… (truncated)" else trimmed
                ToolExecutionResult(out, true)
            }
        } catch (e: Exception) {
            ToolExecutionResult("OCR failed: ${e.message}", false)
        }
    }

    /** Decode with a max-dimension clamp so huge photos don't OOM. */
    private fun decodeScaled(file: File): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val maxSide = maxOf(info.size.width, info.size.height)
                if (maxSide > MAX_DIM) {
                    val scale = MAX_DIM.toFloat() / maxSide
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        } catch (_: Exception) {
            // Fallback path for exotic formats ImageDecoder refuses.
            runCatching {
                BitmapFactory.decodeFile(file.absolutePath)?.let { bmp ->
                    val maxSide = maxOf(bmp.width, bmp.height)
                    if (maxSide > MAX_DIM) {
                        val scale = MAX_DIM.toFloat() / maxSide
                        Bitmap.createScaledBitmap(
                            bmp,
                            (bmp.width * scale).toInt().coerceAtLeast(1),
                            (bmp.height * scale).toInt().coerceAtLeast(1),
                            true,
                        )
                    } else bmp
                }
            }.getOrNull()
        }
    }
}
