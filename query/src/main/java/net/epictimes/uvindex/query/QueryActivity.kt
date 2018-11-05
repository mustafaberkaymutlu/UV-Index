package net.epictimes.uvindex.query

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Color
import android.location.Address
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import com.google.android.material.snackbar.Snackbar
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IValueFormatter
import com.google.android.gms.common.GooglePlayServicesNotAvailableException
import com.google.android.gms.common.GooglePlayServicesRepairableException
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsStatusCodes
import com.google.android.gms.location.SettingsClient
import com.google.android.instantapps.InstantApps
import kotlinx.android.synthetic.main.activity_query.*
import net.epictimes.uvindex.Constants
import net.epictimes.uvindex.data.model.LatLng
import net.epictimes.uvindex.data.model.Weather
import net.epictimes.uvindex.ui.BaseViewStateActivity
import net.epictimes.uvindex.util.getReadableHour
import permissions.dispatcher.NeedsPermission
import permissions.dispatcher.OnPermissionDenied
import permissions.dispatcher.RuntimePermissions
import timber.log.Timber
import java.util.*
import javax.inject.Inject

@RuntimePermissions
class QueryActivity : BaseViewStateActivity<QueryView, QueryPresenter, QueryViewState>(), QueryView {

    companion object {
        private const val LOCATION_INTERVAL: Long = 10000 // update interval in milliseconds
        private const val LOCATION_INTERVAL_FASTEST: Long = LOCATION_INTERVAL / 2

        private const val LINE_CHART_ANIM_DURATION = 1500

        private const val UV_INDEX_MIN = 0
        private const val UV_INDEX_MAX = 11

        fun newIntent(context: Context): Intent = Intent(context, QueryActivity::class.java)
    }

    @Inject
    lateinit var queryPresenter: QueryPresenter

    @Inject
    lateinit var queryViewState: QueryViewState

    private val settingsClient: SettingsClient by lazy { LocationServices.getSettingsClient(this) }
    private val locationSettingsRequest: LocationSettingsRequest by lazy { createLocationSettingsRequest() }

    private val locationRequest: LocationRequest by lazy { createLocationRequest() }
    private val fusedLocationClient: FusedLocationProviderClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val locationCallback: QueryLocationCallback by lazy { QueryLocationCallback() }

    private val chartColors by lazy { createChartColors() }

    override fun createViewState(): QueryViewState = queryViewState

    override fun createPresenter(): QueryPresenter = queryPresenter

    override fun onNewViewStateInstance() = requestLocationUpdatesWithPermissionCheck()

    override fun onCreate(savedInstanceState: Bundle?) {
        DaggerQueryComponent.builder()
                .singletonComponent(singletonComponent)
                .build()
                .inject(this)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_query)
        setSupportActionBar(toolbar)

        imageButtonForecastInfo.setOnClickListener {
            AlertDialog.Builder(this)
                    .setMessage(R.string.forecast_info_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
                    .show()
        }
    }

    override fun onResume() {
        super.onResume()

        if (viewState.locationSearchState == QueryViewState.LocationSearchState.Paused) {
            requestLocationUpdatesWithPermissionCheck()
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates(viewState.locationSearchState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.query, menu)

        val menuItemInstall = menu.findItem(R.id.action_install)
        menuItemInstall.isVisible = InstantApps.isInstantApp(this)

        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == Constants.RequestCodes.UPDATE_LOCATION_SETTINGS) {
            when (resultCode) {
                RESULT_OK -> {
                    // Nothing to do. startLocationUpdates() gets called in onResume again.
                    Timber.i("User agreed to make required location settings changes.")
                    viewState.locationSearchState = QueryViewState.LocationSearchState.Paused
                }
                RESULT_CANCELED -> {
                    Timber.i("User chose not to make required location settings changes.")
                    viewState.locationSearchState = QueryViewState.LocationSearchState.Idle
                    presenter.userDidNotWantToChangeLocationSettings()
                }
            }
        } else if (requestCode == Constants.RequestCodes.START_AUTOCOMPLETE) {
            when (resultCode) {
                RESULT_OK -> {
                    val address = data?.getParcelableExtra<Address>(Constants.BundleKeys.ADDRESS)

                    address?.let {
                        with(viewState) {
                            location = LatLng(it.latitude, it.longitude)
                            locationSearchState = QueryViewState.LocationSearchState.Idle
                        }

                        val location = listOf<String?>(it.adminArea, it.countryName)
                                .filterNot { it.isNullOrBlank() }
                                .joinToString(separator = ", ")

                        presenter.getForecastUvIndex(it.latitude, it.longitude, null, null)

                        Timber.i("Place search operation succeed with place: %s", location)
                    }
                }
                RESULT_CANCELED -> {
                    Timber.i("The user canceled the place search operation.")
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_use_location -> {
            requestLocationUpdatesWithPermissionCheck()
            true
        }
        R.id.action_type_address -> {
            presenter.userClickedTextInputButton()
            true
        }
        R.id.action_install -> {
            presenter.userClickedInstallButton()
            true
        }
        R.id.action_about -> {
            presenter.userClickedAboutButton()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    @SuppressLint("NeedOnRequestPermissionsResult")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        onRequestPermissionsResult(requestCode, grantResults)
    }

    @SuppressLint("MissingPermission")
    @NeedsPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    fun requestLocationUpdates() {
        viewState.locationSearchState = QueryViewState.LocationSearchState.SearchingLocation

        settingsClient.checkLocationSettings(locationSettingsRequest)
                .addOnSuccessListener {
                    fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.myLooper())
                }
                .addOnFailureListener { e ->
                    val statusCode = (e as ApiException).statusCode
                    when (statusCode) {
                        LocationSettingsStatusCodes.RESOLUTION_REQUIRED -> {
                            Timber.i("Location settings are not satisfied. Attempting to upgrade location settings ")
                            try {
                                // Show the dialog by calling startResolutionForResult(), and check the
                                // result in onActivityResult().
                                (e as ResolvableApiException).startResolutionForResult(this@QueryActivity,
                                        Constants.RequestCodes.UPDATE_LOCATION_SETTINGS)
                            } catch (sie: IntentSender.SendIntentException) {
                                Timber.i("PendingIntent unable to execute request.")
                            }
                        }
                        LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE -> {
                            val errorMessage = "Location settings are inadequate, and cannot be fixed here. Fix in Settings."
                            Timber.e(errorMessage)
                            Toast.makeText(this@QueryActivity, errorMessage, Toast.LENGTH_LONG).show()
                            viewState.locationSearchState = QueryViewState.LocationSearchState.Idle
                        }
                    }
                }
    }

    @OnPermissionDenied(Manifest.permission.ACCESS_COARSE_LOCATION)
    fun onLocationPermissionDenied() =
            Snackbar.make(coordinatorLayout, R.string.error_required_location_permission, Snackbar.LENGTH_LONG).show()

    private fun createLocationRequest(): LocationRequest {
        val locationRequest = LocationRequest.create()

        with(locationRequest) {
            interval = LOCATION_INTERVAL
            fastestInterval = LOCATION_INTERVAL_FASTEST
            priority = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
        }

        return locationRequest
    }

    private fun createLocationSettingsRequest() = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .build()

    private fun createChartColors(): List<Int> {
        val typedArray = resources.obtainTypedArray(net.epictimes.uvindex.query.R.array.uv_indexes)
        val colors = ArrayList<Int>()

        (0 until typedArray.length()).mapTo(colors) { typedArray.getColor(it, Color.BLACK) }

        typedArray.recycle()
        return colors
    }

    override fun displayUvIndex(weather: Weather) {
        with(textViewUvIndex) {
            text = Math.round(weather.uvIndex).toInt().toString()
            visibility = View.VISIBLE
        }

        textViewUvIndexDenominator.visibility = View.VISIBLE

        val cardBackgroundColor = chartColors[Math.round(weather.uvIndex).toInt()]
        cardViewCurrent.setCardBackgroundColor(cardBackgroundColor)

        val recommendedProtection: Int
        val info: Int

        when (weather.uvIndex) {
            in 0 until 3 -> {
                recommendedProtection = R.string.recommended_protection_0_3
                info = R.string.info_0_3
            }
            in 3 until 6 -> {
                recommendedProtection = R.string.recommended_protection_3_6
                info = R.string.info_3_6
            }
            in 6 until 8 -> {
                recommendedProtection = R.string.recommended_protection_6_8
                info = R.string.info_6_8
            }
            in 8 until 11 -> {
                recommendedProtection = R.string.recommended_protection_8_11
                info = R.string.info_8_11
            }
            11.0 -> {
                recommendedProtection = R.string.recommended_protection_extreme
                info = R.string.info_extreme
            }
            else -> {
                recommendedProtection = R.string.recommended_protection_0_3
                info = R.string.info_0_3
            }
        }

        textViewRecommendedProtection.setText(recommendedProtection)
        textViewInfo.setText(info)

        Arrays.asList(cardViewForecast, cardViewProtection, cardViewNotes)
                .forEach { it.visibility = View.VISIBLE }
    }

    override fun setToViewState(currentUvIndex: Weather, uvIndexForecast: List<Weather>, timezone: String, address: String) {
        viewState.timezone = timezone
        viewState.currentUvIndex = currentUvIndex
        viewState.address = address

        with(viewState.uvIndexForecast) {
            clear()
            addAll(uvIndexForecast)
        }
    }

    override fun displayUvIndexForecast(uvIndexForecast: List<Weather>) {
        styleLineChart()

        val sliderColors = arrayListOf<Int>()

        uvIndexForecast.mapTo(sliderColors) { chartColors[Math.round(it.uvIndex).toInt()] }

        with(lineChart) {
            data = getForecastLineData(uvIndexForecast)
            animateX(LINE_CHART_ANIM_DURATION)
        }
    }

    override fun displayInstallPrompt(requestCode: Int, referrerCode: String) {
        val isDisplayed = InstantApps.showInstallPrompt(this,
                newIntent(this),
                requestCode,
                referrerCode)

        if (!isDisplayed) {
            Timber.e("InstantApps#displayInstallPrompt failed.")
        }
    }

    override fun displayUserAddress(address: String) {
        val textColor = ContextCompat.getColor(this, android.R.color.black)

        with(textViewLocation) {
            text = address
            setTextColor(textColor)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_my_location_grey_18dp, 0, 0, 0)
        }
    }

    override fun displayAboutUi() =
            Snackbar.make(coordinatorLayout, "Not implemented", Snackbar.LENGTH_LONG).show()

    override fun startPlacesAutoCompleteUi() {
        try {
            val intent = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://epictimes.net/autocomplete/"))
            intent.`package` = packageName
            intent.addCategory(Intent.CATEGORY_BROWSABLE)

            startActivityForResult(intent, Constants.RequestCodes.START_AUTOCOMPLETE)
        } catch (e: Exception) {
            // Kotlin does not support multi-catch yet.
            when (e) {
                is ActivityNotFoundException,
                is GooglePlayServicesRepairableException,
                is GooglePlayServicesNotAvailableException -> {
                    Timber.e(e)
                    presenter.getPlaceAutoCompleteFailed()
                }
                else -> throw e
            }
        }
    }

    override fun displayCantDetectLocationError() =
            Snackbar.make(coordinatorLayout, R.string.error_can_not_detect_location, Snackbar.LENGTH_LONG).show()

    override fun displayGetUvIndexError() =
            Snackbar.make(coordinatorLayout, R.string.error_getting_uv_index, Snackbar.LENGTH_LONG).show()

    override fun displayGetAutoCompletePlaceError() =
            Snackbar.make(coordinatorLayout, R.string.error_getting_autocomplete_place, Snackbar.LENGTH_LONG).show()

    override fun stopLocationUpdates(newState: QueryViewState.LocationSearchState) {
        if (viewState.locationSearchState != QueryViewState.LocationSearchState.SearchingLocation) {
            Timber.d("stopLocationUpdates: updates never requested, no-op.")
            return
        }

        fusedLocationClient.removeLocationUpdates(locationCallback)
                .addOnCompleteListener {
                    viewState.locationSearchState = newState
                }
    }

    private fun styleLineChart() {
        val chartDesc = Description()
        with(chartDesc) {
            text = getString(R.string.chart_desc)
            textColor = ContextCompat.getColor(this@QueryActivity,
                    net.epictimes.uvindex.base.R.color.primary)
        }

        with(lineChart) {
            description = chartDesc
            isDragEnabled = false
            setPinchZoom(false)
            setDrawGridBackground(false) // the background rectangle behind the chart drawing-area
            setDrawBorders(true) // lines surrounding the chart
            setNoDataText(getString(R.string.chart_no_data))
            setNoDataTextColor(ContextCompat.getColor(this@QueryActivity, android.R.color.black))
            setScaleEnabled(false)
            legend.isEnabled = false

            with(xAxis) {
                isEnabled = true
                setAvoidFirstLastClipping(true)
                setDrawLabels(true)
                setValueFormatter { value, _ ->
                    viewState.uvIndexForecast[value.toInt()].datetime.getReadableHour(viewState.timezone)
                }
            }

            with(axisLeft) {
                isEnabled = true
                axisMinimum = UV_INDEX_MIN.toFloat()
                axisMaximum = UV_INDEX_MAX.toFloat()
                setDrawLabels(true)
                setDrawGridLines(true)
                setValueFormatter { value, _ -> String.format("%.0f", value) }
            }

            axisRight.isEnabled = false

            invalidate()
        }
    }

    private fun getForecastLineData(weatherForecast: List<Weather>): LineData {
        val yValues = ArrayList<Entry>()

        weatherForecast
                .forEachIndexed { index, weather ->
                    val entry = Entry(index.toFloat(), weather.uvIndex.toFloat())
                    yValues.add(entry)
                }

        val lineDataSet = UvIndexDataSet(yValues, "All UV Indexes")

        with(lineDataSet) {
            mode = LineDataSet.Mode.LINEAR
            lineWidth = 4f
            setDrawFilled(true)
            circleRadius = 1f
            colors = chartColors
            isHighlightEnabled = false
            valueTextSize = 12f
            setDrawValues(true)
            setDrawHighlightIndicators(false) // disable the drawing of highlight indicator (lines)
            valueFormatter = IValueFormatter { value, _, _, _ ->
                when (value) {
                    0f -> ""
                    else -> String.format("%.0f", value)
                }
            }
        }

        return LineData(lineDataSet)
    }

    inner class QueryLocationCallback : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult?) {
            super.onLocationResult(locationResult)

            locationResult?.lastLocation?.let {
                val latLng = LatLng(it.latitude, it.longitude)
                viewState.location = latLng
                presenter.onLocationReceived(latLng)
            }
        }
    }

}
