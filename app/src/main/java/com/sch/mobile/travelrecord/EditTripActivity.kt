package com.sch.mobile.travelrecord

import android.Manifest
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.sch.mobile.travelrecord.data.Trip
import com.sch.mobile.travelrecord.data.TripPhoto
import com.sch.mobile.travelrecord.data.TripRepository
import com.sch.mobile.travelrecord.media.ImageStorage
import com.sch.mobile.travelrecord.ui.PhotoGridAdapter
import com.sch.mobile.travelrecord.util.DateUtils
import com.sch.mobile.travelrecord.util.SystemBarHelper
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class EditTripActivity : AppCompatActivity() {
    private lateinit var placeInput: TextInputEditText
    private lateinit var dateInput: TextInputEditText
    private lateinit var memoInput: TextInputEditText
    private lateinit var photoProgress: ProgressBar
    private lateinit var photoStatus: TextView
    private lateinit var photoCount: TextView
    private lateinit var photoPickerButton: MaterialButton
    private lateinit var cameraButton: MaterialButton
    private lateinit var saveButton: MaterialButton

    private var repository: TripRepository? = null
    private var photoAdapter: PhotoGridAdapter? = null
    private val imageExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var mediaLocationPermissionLauncher: ActivityResultLauncher<String>

    private var tripId: Long = 0
    private var editingTrip: Trip? = null
    private var currentCameraUri: Uri? = null
    private val selectedPhotos = ArrayList<TripPhoto>()
    private val pendingMediaLocationUris = ArrayList<Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerLaunchers()
        setContentView(R.layout.activity_edit_trip)
        setSupportActionBar(findViewById(R.id.edit_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        SystemBarHelper.apply(this, findViewById(R.id.edit_toolbar), findViewById(R.id.edit_scroll))

        repository = TripRepository(this)
        tripId = intent.getLongExtra(EXTRA_TRIP_ID, 0)
        bindViews()
        setupPhotoGrid()
        bindListeners()

        val restoredDraft = savedInstanceState != null
        if (savedInstanceState != null) {
            restorePhotos(savedInstanceState)
            restoreDraftFields(savedInstanceState)
            val cameraUri = savedInstanceState.getString(STATE_CAMERA_URI)
            if (cameraUri != null) currentCameraUri = Uri.parse(cameraUri)
        }

        if (tripId > 0) {
            setTitle(R.string.title_edit_trip)
            loadTrip(restoredDraft)
        } else {
            setTitle(R.string.title_add_trip)
            if (!restoredDraft) dateInput.setText(DateUtils.today())
            renderPhotos()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        savePhotos(outState)
        outState.putString(STATE_PLACE, textOf(placeInput))
        outState.putString(STATE_DATE, textOf(dateInput))
        outState.putString(STATE_MEMO, textOf(memoInput))
        currentCameraUri?.let { outState.putString(STATE_CAMERA_URI, it.toString()) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        imageExecutor.shutdownNow()
        photoAdapter?.shutdown()
        repository?.close()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == REQUEST_SELECT_IMAGE && data != null) {
            val uris = urisFromResult(data)
            for (uri in uris) tryTakePersistablePermission(uri, data.flags)
            requestMediaLocationThenProcess(uris)
        } else if (requestCode == REQUEST_CAPTURE_IMAGE && currentCameraUri != null) {
            processSelectedImages(arrayListOf(currentCameraUri!!))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraCapture()
            } else {
                Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun registerLaunchers() {
        mediaLocationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Toast.makeText(this, R.string.media_location_permission_needed, Toast.LENGTH_SHORT).show()
            val uris = ArrayList(pendingMediaLocationUris)
            pendingMediaLocationUris.clear()
            processSelectedImages(uris)
        }
    }

    private fun bindViews() {
        placeInput = findViewById(R.id.place_input)
        dateInput = findViewById(R.id.date_input)
        memoInput = findViewById(R.id.memo_input)
        photoProgress = findViewById(R.id.photo_progress)
        photoStatus = findViewById(R.id.photo_status)
        photoCount = findViewById(R.id.photo_count)
        photoPickerButton = findViewById(R.id.photo_picker_button)
        cameraButton = findViewById(R.id.camera_button)
        saveButton = findViewById(R.id.save_button)
    }

    private fun setupPhotoGrid() {
        val photoRecyclerView = findViewById<RecyclerView>(R.id.photo_recycler_view)
        photoRecyclerView.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        photoRecyclerView.setHasFixedSize(false)
        photoAdapter = PhotoGridAdapter(true, this::removePhoto, this::openPhotoViewer)
        photoRecyclerView.adapter = photoAdapter
    }

    private fun bindListeners() {
        findViewById<View>(R.id.date_button).setOnClickListener { showDatePicker() }
        photoPickerButton.setOnClickListener { openPhotoPicker() }
        cameraButton.setOnClickListener { requestCameraCapture() }
        saveButton.setOnClickListener { saveTrip() }
    }

    private fun loadTrip(keepDraftState: Boolean) {
        setBusy(true)
        val repo = repository ?: return
        repo.getTrip(tripId, { trip ->
            if (trip == null) {
                finish()
                return@getTrip
            }
            editingTrip = trip
            if (!keepDraftState) {
                placeInput.setText(trip.place)
                dateInput.setText(trip.visitDate)
                memoInput.setText(trip.memo)
            }
            if (keepDraftState) {
                setBusy(false)
                renderPhotos()
                return@getTrip
            }
            repo.getTripPhotos(tripId, { photos ->
                setBusy(false)
                selectedPhotos.clear()
                selectedPhotos.addAll(photos)
                renderPhotos()
            }, this::showError)
        }, this::showError)
    }

    private fun showDatePicker() {
        val current = DateUtils.parseOrToday(textOf(dateInput))
        val dialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selected = Calendar.getInstance()
                selected.set(year, month, dayOfMonth)
                dateInput.setText(DateUtils.format(selected))
            },
            current.get(Calendar.YEAR),
            current.get(Calendar.MONTH),
            current.get(Calendar.DAY_OF_MONTH),
        )
        dialog.show()
    }

    private fun openPhotoPicker() {
        if (!ensurePhotoCapacity()) return
        val baseIntent = buildContentPickIntent()
        val chooser = Intent.createChooser(baseIntent, null)
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(buildGalleryPickIntent(), buildDocumentPickIntent()))
        if (baseIntent.resolveActivity(packageManager) == null &&
            buildGalleryPickIntent().resolveActivity(packageManager) == null &&
            buildDocumentPickIntent().resolveActivity(packageManager) == null
        ) {
            Toast.makeText(this, R.string.no_image_app, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivityForResult(chooser, REQUEST_SELECT_IMAGE)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_image_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildGalleryPickIntent(): Intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
        setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        addImagePickFlags(this, false)
    }

    private fun buildDocumentPickIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
        addImagePickFlags(this, true)
    }

    private fun buildContentPickIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT).apply {
        type = "image/*"
        addImagePickFlags(this, false)
    }

    private fun addImagePickFlags(intent: Intent, persistable: Boolean) {
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (persistable) flags = flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        intent.addFlags(flags)
    }

    private fun requestCameraCapture() {
        if (!ensurePhotoCapacity()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraCapture()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
    }

    private fun startCameraCapture() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val cameraFile = ImageStorage.createCameraFile(this)
            currentCameraUri = ImageStorage.getCameraUri(this, cameraFile)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, currentCameraUri)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivityForResult(intent, REQUEST_CAPTURE_IMAGE)
        } catch (exception: Exception) {
            showError(exception)
        }
    }

    private fun requestMediaLocationThenProcess(uris: List<Uri>?) {
        if (uris.isNullOrEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasMediaLocationPermission()) {
            pendingMediaLocationUris.clear()
            pendingMediaLocationUris.addAll(uris)
            mediaLocationPermissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            return
        }
        processSelectedImages(uris)
    }

    private fun processSelectedImages(sourceUris: List<Uri>) {
        val remaining = MAX_PHOTOS - selectedPhotos.size
        if (remaining <= 0) {
            showPhotoLimitMessage()
            return
        }
        val uris = ArrayList<Uri>()
        for (uri in sourceUris) {
            if (uris.size >= remaining) break
            uris.add(uri)
        }
        if (sourceUris.size > uris.size) {
            Toast.makeText(this, getString(R.string.photo_partial_message, sourceUris.size, uris.size, MAX_PHOTOS), Toast.LENGTH_SHORT).show()
        }
        if (uris.isEmpty()) return

        setBusy(true)
        photoStatus.setText(R.string.photo_processing)
        imageExecutor.execute {
            val processed = ArrayList<TripPhoto>()
            for (uri in uris) {
                try {
                    val storedImage = ImageStorage.copyToInternalStorage(this, uri, hasMediaLocationPermission())
                    val photo = TripPhoto(
                        0,
                        tripId,
                        storedImage.uriString,
                        storedImage.latitude,
                        storedImage.longitude,
                        storedImage.originalName,
                        storedImage.width,
                        storedImage.height,
                        selectedPhotos.isEmpty() && processed.isEmpty(),
                        selectedPhotos.size + processed.size,
                        System.currentTimeMillis(),
                    )
                    processed.add(photo)
                } catch (_: Exception) {
                    mainHandler.post { Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show() }
                }
            }
            mainHandler.post {
                selectedPhotos.addAll(processed)
                reindexPhotos()
                renderPhotos()
                setBusy(false)
            }
        }
    }

    private fun renderPhotos() {
        photoAdapter?.setPhotos(selectedPhotos)
        photoCount.text = getString(R.string.photo_count_format, selectedPhotos.size, MAX_PHOTOS)
        var gpsCount = 0
        for (photo in selectedPhotos) if (photo.hasLocation()) gpsCount++
        if (selectedPhotos.isEmpty()) {
            photoStatus.setText(R.string.no_photo_message)
        } else {
            photoStatus.text = getString(R.string.photo_location_summary_format, gpsCount, selectedPhotos.size)
        }
    }

    private fun removePhoto(photo: TripPhoto) {
        selectedPhotos.remove(photo)
        if (photo.id <= 0) ImageStorage.deleteStoredImage(this, photo.uriString)
        reindexPhotos()
        renderPhotos()
    }

    private fun openPhotoViewer(photo: TripPhoto) {
        val index = selectedPhotos.indexOf(photo)
        PhotoViewerActivity.open(this, selectedPhotos, max(0, index))
    }

    private fun saveTrip() {
        val place = textOf(placeInput)
        val visitDate = textOf(dateInput)
        if (place.isEmpty()) {
            placeInput.error = getString(R.string.place_required)
            return
        }
        if (visitDate.isEmpty()) {
            dateInput.error = getString(R.string.date_required)
            return
        }

        val trip = editingTrip ?: Trip()
        trip.no = tripId
        trip.place = place
        trip.visitDate = visitDate
        trip.memo = textOf(memoInput)
        applyPrimaryPhotoToTrip(trip)

        setBusy(true)
        repository?.saveTripWithPhotos(trip, ArrayList(selectedPhotos), {
            Toast.makeText(this, R.string.trip_saved, Toast.LENGTH_SHORT).show()
            finish()
        }, this::showError)
    }

    private fun applyPrimaryPhotoToTrip(trip: Trip) {
        if (selectedPhotos.isEmpty()) {
            trip.photoUri = null
            trip.latitude = null
            trip.longitude = null
            trip.photoCount = 0
            trip.gpsPhotoCount = 0
            return
        }
        val primary = selectedPhotos[0]
        trip.photoUri = primary.uriString
        trip.latitude = primary.latitude
        trip.longitude = primary.longitude
        trip.photoCount = selectedPhotos.size
        var gpsCount = 0
        for (photo in selectedPhotos) if (photo.hasLocation()) gpsCount++
        trip.gpsPhotoCount = gpsCount
    }

    private fun reindexPhotos() {
        for (i in selectedPhotos.indices) {
            val photo = selectedPhotos[i]
            photo.primary = i == 0
            photo.sortOrder = i
        }
    }

    private fun urisFromResult(data: Intent): List<Uri> {
        val uris = ArrayList<Uri>()
        val clipData: ClipData? = data.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                val uri = clipData.getItemAt(i).uri
                if (uri != null) uris.add(uri)
            }
        } else if (data.data != null) {
            uris.add(data.data!!)
        }
        return uris
    }

    private fun ensurePhotoCapacity(): Boolean {
        if (selectedPhotos.size >= MAX_PHOTOS) {
            showPhotoLimitMessage()
            return false
        }
        return true
    }

    private fun showPhotoLimitMessage() {
        Toast.makeText(this, getString(R.string.photo_limit_message, MAX_PHOTOS), Toast.LENGTH_SHORT).show()
    }

    private fun hasMediaLocationPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun tryTakePersistablePermission(uri: Uri, flags: Int) {
        val takeFlags = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (takeFlags == 0) return
        try {
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (_: SecurityException) {
            // 내부 저장소로 복사하므로 영구 권한 확보 실패는 치명적이지 않다.
        }
    }

    private fun setBusy(busy: Boolean) {
        photoProgress.visibility = if (busy) View.VISIBLE else View.GONE
        saveButton.isEnabled = !busy
        photoPickerButton.isEnabled = !busy
        cameraButton.isEnabled = !busy
    }

    private fun showError(throwable: Throwable) {
        setBusy(false)
        Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
    }

    private fun textOf(editText: TextInputEditText): String = editText.text?.toString()?.trim().orEmpty()

    private fun savePhotos(outState: Bundle) {
        val uris = ArrayList<String>()
        val names = ArrayList<String?>()
        val lats = DoubleArray(selectedPhotos.size)
        val lngs = DoubleArray(selectedPhotos.size)
        val widths = IntArray(selectedPhotos.size)
        val heights = IntArray(selectedPhotos.size)
        for (i in selectedPhotos.indices) {
            val photo = selectedPhotos[i]
            uris.add(photo.uriString.orEmpty())
            names.add(photo.originalName)
            lats[i] = photo.latitude ?: Double.NaN
            lngs[i] = photo.longitude ?: Double.NaN
            widths[i] = photo.width
            heights[i] = photo.height
        }
        outState.putStringArrayList(STATE_PHOTO_URIS, uris)
        outState.putStringArrayList(STATE_PHOTO_NAMES, ArrayList(names))
        outState.putDoubleArray(STATE_PHOTO_LATS, lats)
        outState.putDoubleArray(STATE_PHOTO_LNGS, lngs)
        outState.putIntArray(STATE_PHOTO_WIDTHS, widths)
        outState.putIntArray(STATE_PHOTO_HEIGHTS, heights)
    }

    private fun restorePhotos(savedInstanceState: Bundle) {
        val uris = savedInstanceState.getStringArrayList(STATE_PHOTO_URIS) ?: return
        val names = savedInstanceState.getStringArrayList(STATE_PHOTO_NAMES)
        val lats = savedInstanceState.getDoubleArray(STATE_PHOTO_LATS)
        val lngs = savedInstanceState.getDoubleArray(STATE_PHOTO_LNGS)
        val widths = savedInstanceState.getIntArray(STATE_PHOTO_WIDTHS)
        val heights = savedInstanceState.getIntArray(STATE_PHOTO_HEIGHTS)
        selectedPhotos.clear()
        for (i in uris.indices) {
            val lat = if (lats != null && i < lats.size && !lats[i].isNaN()) lats[i] else null
            val lng = if (lngs != null && i < lngs.size && !lngs[i].isNaN()) lngs[i] else null
            val width = if (widths != null && i < widths.size) widths[i] else 0
            val height = if (heights != null && i < heights.size) heights[i] else 0
            val name = if (names != null && i < names.size) names[i] else "photo.jpg"
            selectedPhotos.add(TripPhoto(0, tripId, uris[i], lat, lng, name, width, height, i == 0, i, System.currentTimeMillis()))
        }
    }

    private fun restoreDraftFields(savedInstanceState: Bundle) {
        placeInput.setText(savedInstanceState.getString(STATE_PLACE, ""))
        dateInput.setText(savedInstanceState.getString(STATE_DATE, ""))
        memoInput.setText(savedInstanceState.getString(STATE_MEMO, ""))
    }

    companion object {
        const val EXTRA_TRIP_ID = "com.sch.mobile.travelrecord.EXTRA_TRIP_ID"

        private const val REQUEST_SELECT_IMAGE = 1001
        private const val REQUEST_CAPTURE_IMAGE = 1002
        private const val REQUEST_CAMERA_PERMISSION = 1003
        private const val MAX_PHOTOS = 30
        private const val STATE_CAMERA_URI = "state_camera_uri"
        private const val STATE_PHOTO_URIS = "state_photo_uris"
        private const val STATE_PHOTO_NAMES = "state_photo_names"
        private const val STATE_PHOTO_LATS = "state_photo_lats"
        private const val STATE_PHOTO_LNGS = "state_photo_lngs"
        private const val STATE_PHOTO_WIDTHS = "state_photo_widths"
        private const val STATE_PHOTO_HEIGHTS = "state_photo_heights"
        private const val STATE_PLACE = "state_place"
        private const val STATE_DATE = "state_date"
        private const val STATE_MEMO = "state_memo"
    }
}
