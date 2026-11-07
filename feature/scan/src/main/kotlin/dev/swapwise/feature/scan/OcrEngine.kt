package dev.swapwise.feature.scan

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.swapwise.data.ReceiptRepository
import dev.swapwise.domain.OcrFragment
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads text from an image. An interface so the ViewModel can be tested without ML Kit or a device; the image is
 * passed as a URI string because android.net.Uri cannot be created in plain JVM tests.
 */
fun interface OcrEngine {
    suspend fun recognize(imageUri: String): List<OcrFragment>
}

/** On-device ML Kit text recognition (bundled model: works offline, the photo never leaves the phone). */
@Singleton
class MlKitOcrEngine @Inject constructor(@ApplicationContext private val context: Context) : OcrEngine {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(imageUri: String): List<OcrFragment> {
        val input = InputImage.fromFilePath(context, Uri.parse(imageUri)) // also applies the photo's EXIF rotation
        val text = suspendCancellableCoroutine { continuation ->
            recognizer.process(input)
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
        // Line level, not block level: blocks can merge a whole column of names or prices.
        return text.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            OcrFragment(line.text, box.left, box.top, box.bottom)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ScanModule {
    @Binds
    abstract fun ocrEngine(engine: MlKitOcrEngine): OcrEngine

    companion object {
        @Provides
        fun receiptSaver(repository: ReceiptRepository): ReceiptSaver = ReceiptSaver { rows -> repository.saveScannedReceipt(rows) }
    }
}
