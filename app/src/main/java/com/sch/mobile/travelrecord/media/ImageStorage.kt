package com.sch.mobile.travelrecord.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

object ImageStorage {
    private const val PHOTO_DIR_NAME = "trip_photos"
    private const val CACHE_DIR_NAME = "trip_photo_cache"
    private const val THUMBNAIL_MAX_SIDE = 320
    private const val PREVIEW_MAX_SIDE = 1600

    class StoredImage internal constructor(
        @JvmField val uriString: String,
        @JvmField val latitude: Double?,
        @JvmField val longitude: Double?,
        @JvmField val originalName: String,
        @JvmField val width: Int,
        @JvmField val height: Int,
    )

    @JvmStatic
    @Throws(Exception::class)
    fun createCameraFile(context: Context): File {
        val dir = File(context.cacheDir, "camera")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("camera cache directory create failed")
        }
        return File.createTempFile("trip_camera_", ".jpg", dir)
    }

    @JvmStatic
    fun getCameraUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    @JvmStatic
    @Throws(Exception::class)
    fun copyToInternalStorage(context: Context, sourceUri: Uri, canAccessMediaLocation: Boolean): StoredImage {
        val originalName = queryDisplayName(context, sourceUri)
        var gps = extractGps(context, sourceUri, canAccessMediaLocation)

        val dir = File(context.filesDir, PHOTO_DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("photo directory create failed")
        }
        val dest = File.createTempFile("trip_", extensionFromName(originalName), dir)
        context.contentResolver.openInputStream(sourceUri).use { inputStream ->
            FileOutputStream(dest).use { outputStream ->
                if (inputStream == null) throw IllegalArgumentException("image input stream is null")
                val buffer = ByteArray(8192)
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    outputStream.write(buffer, 0, read)
                }
            }
        }

        val size = decodeImageSize(dest)
        if (gps[0] == null || gps[1] == null) {
            val copiedGps = extractGpsFromFile(dest)
            if (copiedGps[0] != null && copiedGps[1] != null) gps = copiedGps
        }
        // 저장 직후 작은 파생 이미지를 만들어 두면 목록/지도 첫 표시 때 원본 디코딩을 줄일 수 있다.
        val storedUri = Uri.fromFile(dest).toString()
        ensureImageVariant(context, storedUri, THUMBNAIL_MAX_SIDE, "thumb")
        ensureImageVariant(context, storedUri, PREVIEW_MAX_SIDE, "preview")
        return StoredImage(storedUri, gps[0], gps[1], originalName, size[0], size[1])
    }

    @JvmStatic
    fun deleteStoredImage(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(uriString)
            if (ContentResolver.SCHEME_FILE != uri.scheme) return false
            val file = File(uri.path ?: "").canonicalFile
            val photoDir = File(context.filesDir, PHOTO_DIR_NAME).canonicalFile
            val photoDirPath = photoDir.path
            val filePath = file.path
            if (filePath != photoDirPath && !filePath.startsWith(photoDirPath + File.separator)) return false
            val deleted = file.exists() && file.delete()
            // 원본을 지울 때 재생성 가능한 썸네일/미리보기 캐시도 함께 정리해 저장공간을 아낀다.
            deleteVariantFile(context, uriString, "thumb")
            deleteVariantFile(context, uriString, "preview")
            deleted
        } catch (_: Exception) {
            false
        }
    }

    @JvmStatic
    fun getOrCreateThumbnailUri(context: Context, uriString: String): Uri = ensureImageVariant(context, uriString, THUMBNAIL_MAX_SIDE, "thumb")

    @JvmStatic
    fun getOrCreatePreviewUri(context: Context, uriString: String): Uri = ensureImageVariant(context, uriString, PREVIEW_MAX_SIDE, "preview")

    @JvmStatic
    fun decodeSampledBitmap(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int, lowMemory: Boolean): Bitmap? {
        return try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            context.contentResolver.openInputStream(uri).use { boundsStream ->
                if (boundsStream == null) return null
                BitmapFactory.decodeStream(boundsStream, null, options)
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            if (lowMemory) options.inPreferredConfig = Bitmap.Config.RGB_565
            context.contentResolver.openInputStream(uri).use { imageStream ->
                if (imageStream == null) return null
                BitmapFactory.decodeStream(imageStream, null, options)
            }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    private fun ensureImageVariant(context: Context, uriString: String?, maxSide: Int, variantName: String): Uri {
        if (uriString.isNullOrBlank()) return Uri.EMPTY
        val normalizedUriString = uriString.trim()
        return try {
            val originalUri = Uri.parse(normalizedUriString)
            val variantFile = getVariantFile(context, normalizedUriString, variantName)
            if (variantFile.exists() && variantFile.length() > 0) {
                if (isUsableBitmapFile(variantFile)) return Uri.fromFile(variantFile)
                // 이전 실행에서 끊긴 캐시가 남았으면 삭제하고 원본에서 다시 만든다.
                variantFile.delete()
            }
            if (ContentResolver.SCHEME_FILE != originalUri.scheme) return originalUri
            val originalFile = File(originalUri.path ?: "").canonicalFile
            val photoDir = File(context.filesDir, PHOTO_DIR_NAME).canonicalFile
            val photoDirPath = photoDir.path
            val originalPath = originalFile.path
            if (originalPath != photoDirPath && !originalPath.startsWith(photoDirPath + File.separator)) return originalUri

            val bitmap = decodeSampledBitmap(context, originalUri, maxSide, maxSide, maxSide <= THUMBNAIL_MAX_SIDE) ?: return originalUri
            val output = scaleDown(bitmap, maxSide)
            if (variantFile.parentFile != null && !variantFile.parentFile!!.exists()) {
                // 캐시 폴더는 필요할 때만 만들고, 실패하면 원본 표시로 자연스럽게 되돌린다.
                variantFile.parentFile!!.mkdirs()
            }
            val tempFile = File(variantFile.path + ".tmp")
            val compressed = FileOutputStream(tempFile).use { outputStream ->
                output.compress(Bitmap.CompressFormat.JPEG, if (maxSide <= THUMBNAIL_MAX_SIDE) 82 else 88, outputStream)
            }
            if (output !== bitmap) output.recycle()
            bitmap.recycle()
            if (!compressed || tempFile.length() <= 0) {
                tempFile.delete()
                variantFile.delete()
                return originalUri
            }
            // 임시 파일에 완성한 뒤 교체해 중간에 끊긴 JPEG 캐시를 다음 실행에서 신뢰하지 않게 한다.
            if (variantFile.exists() && !variantFile.delete()) {
                tempFile.delete()
                return originalUri
            }
            if (!tempFile.renameTo(variantFile)) {
                tempFile.delete()
                return originalUri
            }
            if (variantFile.exists() && variantFile.length() > 0) Uri.fromFile(variantFile) else originalUri
        } catch (_: Exception) {
            Uri.parse(normalizedUriString)
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val longSide = max(width, height)
        if (longSide <= maxSide || width <= 0 || height <= 0) return bitmap
        val ratio = maxSide / longSide.toFloat()
        val targetWidth = max(1, (width * ratio).roundToInt())
        val targetHeight = max(1, (height * ratio).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun deleteVariantFile(context: Context, uriString: String, variantName: String) {
        try {
            val variantFile = getVariantFile(context, uriString.trim(), variantName).canonicalFile
            val cacheDir = File(context.filesDir, CACHE_DIR_NAME).canonicalFile
            val cacheDirPath = cacheDir.path
            val variantPath = variantFile.path
            if ((variantPath == cacheDirPath || variantPath.startsWith(cacheDirPath + File.separator)) && variantFile.exists()) {
                variantFile.delete()
            }
        } catch (_: Exception) {
            // 캐시는 재생성 가능하므로 삭제 실패가 저장 흐름을 막지 않게 한다.
        }
    }

    @Throws(Exception::class)
    private fun getVariantFile(context: Context, uriString: String, variantName: String): File {
        val cacheDir = File(context.filesDir, CACHE_DIR_NAME)
        return File(cacheDir, variantName + "_" + sha1(uriString) + ".jpg")
    }

    private fun isUsableBitmapFile(file: File): Boolean {
        return try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0
        } catch (_: Exception) {
            false
        }
    }

    @Throws(Exception::class)
    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(bytes.size * 2)
        for (b in bytes) builder.append(String.format(Locale.ROOT, "%02x", b))
        return builder.toString()
    }

    private fun extractGps(context: Context, sourceUri: Uri, canAccessMediaLocation: Boolean): Array<Double?> {
        var exifUri = sourceUri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canAccessMediaLocation) {
            try {
                val mediaUri = MediaStore.getMediaUri(context, sourceUri)
                exifUri = MediaStore.setRequireOriginal(mediaUri ?: sourceUri)
            } catch (_: Exception) {
                try {
                    exifUri = MediaStore.setRequireOriginal(sourceUri)
                } catch (_: Exception) {
                    exifUri = sourceUri
                }
            }
        }
        try {
            context.contentResolver.openInputStream(exifUri).use { inputStream ->
                if (inputStream == null) return arrayOf(null, null)
                val exifInterface = ExifInterface(inputStream)
                val latLong = exifInterface.latLong
                if (latLong != null && latLong.size == 2) return arrayOf(latLong[0], latLong[1])
            }
        } catch (_: Exception) {
            // 위치 권한, 제공자 정책, EXIF 부재 모두 GPS 없는 사진으로 처리한다.
        }
        return arrayOf(null, null)
    }

    private fun extractGpsFromFile(file: File): Array<Double?> {
        try {
            FileInputStream(file).use { inputStream ->
                val exifInterface = ExifInterface(inputStream as InputStream)
                val latLong = exifInterface.latLong
                if (latLong != null && latLong.size == 2) return arrayOf(latLong[0], latLong[1])
            }
        } catch (_: Exception) {
            // EXIF가 없거나 읽을 수 없는 이미지는 GPS 없는 사진으로 처리한다.
        }
        return arrayOf(null, null)
    }

    private fun decodeImageSize(file: File): IntArray {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, options)
        val width = max(0, options.outWidth)
        val height = max(0, options.outHeight)
        return intArrayOf(width, height)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return max(1, inSampleSize)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        if (ContentResolver.SCHEME_FILE == uri.scheme) return File(uri.path ?: "").name
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        } catch (_: Exception) {
            // 이름 조회 실패 시 기본 이름을 사용한다.
        }
        return "photo.jpg"
    }

    private fun extensionFromName(name: String?): String {
        if (name == null) return ".jpg"
        val dot = name.lastIndexOf('.')
        if (dot >= 0 && dot < name.length - 1) {
            val ext = name.substring(dot).lowercase(Locale.ROOT)
            if (ext.length <= 6) return ext
        }
        return ".jpg"
    }
}
