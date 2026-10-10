package com.aicamera.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Root() } }
    }
}

@Composable
fun Root() {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (granted) CameraScreen()
    else Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Permita o acesso à câmera") } }
}

@Composable
fun CameraScreen() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            ).build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var s by remember { mutableFloatStateOf(0f) }
    var burst by remember { mutableIntStateOf(5) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    val zoom = 100f.pow(s) // escala logarítmica: 1x a 100x
    val hwMax = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
    LaunchedEffect(zoom, hwMax, camera) { camera?.cameraControl?.setZoomRatio(zoom.coerceIn(1f, hwMax)) }

    Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AndroidView(
                factory = { c ->
                    val pv = PreviewView(c)
                    val f = ProcessCameraProvider.getInstance(c)
                    f.addListener({
                        try {
                            val p = f.get()
                            val prev = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                            p.unbindAll()
                            camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, prev, capture)
                        } catch (e: Exception) { status = "Erro na câmera: ${e.message}" }
                    }, ContextCompat.getMainExecutor(c))
                    pv
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f)
            )
            Spacer(Modifier.height(8.dp))
            Text("%.1f×".format(zoom), style = MaterialTheme.typography.titleLarge)
            Text(
                if (zoom <= hwMax) "Zoom da câmera (digital pelo ISP, sem IA)"
                else "Acima de %.1f×: recorte ampliado. Detalhes novos não são reais.".format(hwMax),
                style = MaterialTheme.typography.bodySmall
            )
            Slider(value = s, onValueChange = { s = it }, enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 8).forEach { n ->
                    FilterChip(
                        selected = burst == n, onClick = { burst = n }, enabled = !busy,
                        label = { Text(if (n == 1) "1 foto" else "Rajada $n") }
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(status, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.size(72.dp)
                    .background(if (busy) Color.Gray else Color.White, CircleShape)
                    .clickable(enabled = !busy) {
                        val z = zoom; val hw = hwMax; val n = burst
                        busy = true
                        scope.launch {
                            val files = mutableListOf<File>()
                            try {
                                for (i in 1..n) {
                                    status = "Capturando $i/$n…"
                                    val f = File(ctx.cacheDir, "b$i.jpg")
                                    if (takeOne(ctx, capture, f)) files += f
                                }
                                status = "Processando…"
                                val name = "AI_${System.currentTimeMillis()}_${z.toInt()}x.jpg"
                                process(ctx, files, z / z.coerceAtMost(hw), name)
                                status = "Salva em Pictures/AICamera"
                            } catch (e: Throwable) { status = "Erro: ${e.message}" }
                            files.forEach { it.delete() }
                            busy = false
                        }
                    }
            )
        }
    }
}

suspend fun takeOne(ctx: Context, cap: ImageCapture, f: File): Boolean =
    suspendCancellableCoroutine { c ->
        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(f).build(),
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) { c.resume(true) }
                override fun onError(e: ImageCaptureException) { c.resume(false) }
            }
        )
    }

// Decodifica só o recorte central (1/extra da imagem), sem carregar a foto inteira na memória.
fun decodeCrop(path: String, extra: Float, maxW: Int): Bitmap {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    val w = o.outWidth; val h = o.outHeight
    val cw = (w / extra).toInt().coerceAtLeast(16)
    val ch = (h / extra).toInt().coerceAtLeast(16)
    val rect = Rect((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2)
    var sample = 1
    while (cw / (sample * 2) >= maxW) sample *= 2
    @Suppress("DEPRECATION")
    val dec = BitmapRegionDecoder.newInstance(path, false)
    val bmp = dec!!.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample })
    dec.recycle()
    return bmp
}

// Nitidez = variância do Laplaciano (quanto maior, menos tremido/borrado).
fun sharpness(b: Bitmap): Double {
    val w = b.width; val h = b.height
    val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
    val g = IntArray(w * h) { ((px[it] shr 16 and 255) + (px[it] shr 8 and 255) + (px[it] and 255)) / 3 }
    var sum = 0.0; var sq = 0.0; var n = 0
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val i = y * w + x
        val l = (4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]).toDouble()
        sum += l; sq += l * l; n++
    }
    return if (n == 0) 0.0 else sq / n - (sum / n) * (sum / n)
}

fun sharpen(src: Bitmap, a: Float = 0.5f): Bitmap {
    val w = src.width; val h = src.height
    val p = IntArray(w * h); src.getPixels(p, 0, w, 0, 0, w, h)
    val o = p.copyOf()
    val shifts = intArrayOf(16, 8, 0)
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val i = y * w + x
        var out = 0xFF shl 24
        for (sh in shifts) {
            val c = (p[i] shr sh) and 255
            val nb = ((p[i - 1] shr sh) and 255) + ((p[i + 1] shr sh) and 255) +
                ((p[i - w] shr sh) and 255) + ((p[i + w] shr sh) and 255)
            out = out or ((c + a * (4 * c - nb)).toInt().coerceIn(0, 255) shl sh)
        }
        o[i] = out
    }
    return Bitmap.createBitmap(o, w, h, Bitmap.Config.ARGB_8888)
}

suspend fun process(ctx: Context, files: List<File>, extra: Float, name: String) = withContext(Dispatchers.Default) {
    require(files.isNotEmpty()) { "nenhuma foto capturada" }
    // 1) escolhe o quadro mais nítido da rajada
    val best = files.maxByOrNull { sharpness(decodeCrop(it.path, extra, 512)) }!!
    // 2) recorte digital (se o zoom passou do máximo da câmera) e ampliação até 3200 px
    var b = decodeCrop(best.path, extra, 3200)
    if (b.width < 3200) b = Bitmap.createScaledBitmap(b, 3200, b.height * 3200 / b.width, true)
    // 3) orientação EXIF
    val deg = when (ExifInterface(best.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
        6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
    }
    if (deg != 0f) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
    // 4) nitidez leve e salvar na galeria
    b = sharpen(b)
    val v = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AICamera")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
    uri?.let { u -> ctx.contentResolver.openOutputStream(u)?.use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
}
