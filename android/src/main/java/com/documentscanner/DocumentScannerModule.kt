package com.documentscanner

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.module.annotations.ReactModule
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.Page
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.Objects

@ReactModule(name = DocumentScannerModule.NAME)
class DocumentScannerModule(reactContext: ReactApplicationContext) :
  NativeDocumentScannerSpec(reactContext) {

  override fun getName(): String {
    return NAME
  }

  @Throws(FileNotFoundException::class)
  fun getImageInBase64(currentActivity: Activity, croppedImageUri: Uri, quality: Int): String {
    val bitmap = BitmapFactory.decodeStream(
      currentActivity.contentResolver.openInputStream(croppedImageUri)
    )
    val byteArrayOutputStream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream)
    val byteArray = byteArrayOutputStream.toByteArray()
    return Base64.encodeToString(byteArray, Base64.DEFAULT)
  }

  @Throws(FileNotFoundException::class)
  fun getImageInBase64WithPadding(currentActivity: Activity, croppedImageUri: Uri, quality: Int, paddingRatio: Int): String {
    val originalBitmap = BitmapFactory.decodeStream(
      currentActivity.contentResolver.openInputStream(croppedImageUri)
    )
    val paddedBitmap = addPaddingToBitmap(originalBitmap, paddingRatio)
    val byteArrayOutputStream = ByteArrayOutputStream()
    paddedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream)
    val byteArray = byteArrayOutputStream.toByteArray()
    return Base64.encodeToString(byteArray, Base64.DEFAULT)
  }

  fun addPaddingToBitmap(originalBitmap: Bitmap, paddingRatio: Int): Bitmap {
    // Calculate padding based on ratio (1-10)
    // Ratio 1 = 2% padding, Ratio 10 = 20% padding
    val paddingPercentage = (paddingRatio * 2).coerceIn(2, 20) / 100.0
    val paddingX = (originalBitmap.width * paddingPercentage).toInt()
    val paddingY = (originalBitmap.height * paddingPercentage).toInt()
    
    // Create new bitmap with padding
    val newWidth = originalBitmap.width + (paddingX * 2)
    val newHeight = originalBitmap.height + (paddingY * 2)
    val paddedBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
    
    // Fill with white background
    val canvas = Canvas(paddedBitmap)
    canvas.drawColor(Color.WHITE)
    
    // Draw original bitmap centered with padding
    canvas.drawBitmap(originalBitmap, paddingX.toFloat(), paddingY.toFloat(), null)
    
    return paddedBitmap
  }

  @Throws(FileNotFoundException::class)
  fun createPaddedImageFile(currentActivity: Activity, croppedImageUri: Uri, quality: Int, paddingRatio: Int): String {
    val originalBitmap = BitmapFactory.decodeStream(
      currentActivity.contentResolver.openInputStream(croppedImageUri)
    )
    val paddedBitmap = addPaddingToBitmap(originalBitmap, paddingRatio)
    
    // Create a temporary file for the padded image
    val cacheDir = currentActivity.cacheDir
    val paddedImageFile = File(cacheDir, "padded_document_${System.currentTimeMillis()}.jpg")
    
    val fileOutputStream = FileOutputStream(paddedImageFile)
    paddedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, fileOutputStream)
    fileOutputStream.close()
    
    return paddedImageFile.absolutePath
  }

  override fun scanDocument(options: ReadableMap, promise: Promise) {
    val currentActivity = reactApplicationContext.getCurrentActivity()
    val response: WritableMap = WritableNativeMap()

    val documentScannerOptionsBuilder = GmsDocumentScannerOptions.Builder()
      .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
      .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)

    if (options.hasKey("maxNumDocuments")) {
      documentScannerOptionsBuilder.setPageLimit(
        options.getInt("maxNumDocuments")
      )
    }

    val croppedImageQuality: Int = if (options.hasKey("croppedImageQuality")) {
      options.getInt("croppedImageQuality")
    } else {
      100
    }

    val paddingRatio: Int? = if (options.hasKey("paddingRatio")) {
      options.getInt("paddingRatio").coerceIn(1, 10)
    } else {
      null
    }

    val scanner: GmsDocumentScanner =
      GmsDocumentScanning.getClient(documentScannerOptionsBuilder.build())
    val scannerLauncher: ActivityResultLauncher<IntentSenderRequest?> =
      (currentActivity as ComponentActivity).activityResultRegistry.register(
        "document-scanner",
        StartIntentSenderForResult(),
        { result ->
          if (result.resultCode == Activity.RESULT_OK) {
            val documentScanningResult: GmsDocumentScanningResult? =
              GmsDocumentScanningResult.fromActivityResultIntent(
                result.data
              )
            val docScanResults: WritableArray = WritableNativeArray()

            if (documentScanningResult != null) {
              val pages: MutableList<Page>? = documentScanningResult.pages
              if (pages != null) {
                for (page in pages) {
                  val croppedImageUri: Uri = page.imageUri
                  var croppedImageResults: String? = croppedImageUri.toString()

                  try {
                    if (options.hasKey("responseType") && Objects.equals(
                        options.getString("responseType"),
                        "base64"
                      )
                    ) {
                      // Base64 response with optional padding
                      croppedImageResults = if (paddingRatio != null) {
                        this.getImageInBase64WithPadding(currentActivity, croppedImageUri, croppedImageQuality, paddingRatio)
                      } else {
                        this.getImageInBase64(currentActivity, croppedImageUri, croppedImageQuality)
                      }
                    } else if (paddingRatio != null) {
                      // File path response with padding - create padded file
                      croppedImageResults = this.createPaddedImageFile(currentActivity, croppedImageUri, croppedImageQuality, paddingRatio)
                    }
                    // If no padding and file path response, use original URI string (default behavior)
                  } catch (error: FileNotFoundException) {
                    promise.reject("document scan error", error.message)
                    return@register
                  }

                  docScanResults.pushString(croppedImageResults)
                }
              }
            }

            response.putArray(
              "scannedImages",
              docScanResults
            )
            response.putString("status", "success")
            promise.resolve(response)
          } else if (result.resultCode == Activity.RESULT_CANCELED) {
            // when user cancels document scan
            response.putString("status", "cancel")
            promise.resolve(response)
          }
        }
      )

    scanner.getStartScanIntent(currentActivity)
      .addOnSuccessListener({ intentSender ->
        scannerLauncher.launch(
          IntentSenderRequest.Builder(
            intentSender
          ).build()
        )
      })
      .addOnFailureListener({ error ->
        // document scan error
        promise.reject("document scan error", error.message)
      })
  }

  companion object {
    const val NAME = "DocumentScanner"
  }
}
