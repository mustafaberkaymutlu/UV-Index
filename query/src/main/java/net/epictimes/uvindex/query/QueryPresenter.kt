package net.epictimes.uvindex.query


import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter
import net.epictimes.uvindex.Constants
import net.epictimes.uvindex.data.interactor.WeatherInteractor
import net.epictimes.uvindex.data.model.LatLng
import net.epictimes.uvindex.data.model.Weather
import java.util.Collections
import javax.inject.Inject

class QueryPresenter @Inject constructor(
    private val weatherInteractor: WeatherInteractor,
    private val dateProvider: DateProvider
): MvpBasePresenter<QueryView>() {

    companion object {
        const val FORECAST_HOUR = 24
    }

    fun getForecastUvIndex(latitude: Double, longitude: Double, language: String?, units: String?) {
        weatherInteractor.getForecast(latitude, longitude, language, units, FORECAST_HOUR,
                object : WeatherInteractor.GetForecastCallback {
                    override fun onSuccessGetForecast(weatherForecast: List<Weather>,
                                                      timezone: String,
                                                      cityName: String,
                                                      countryCode: String) {
                        if (weatherForecast.isEmpty()) {
                            ifViewAttached { it.displayGetUvIndexError() }
                        } else {
                            val sortedForecast = weatherForecast.sortedBy { it.datetime.time }
                            val currentWeather = getClosestWeather(weatherForecast)

                            val address = "$cityName, $countryCode"

                            ifViewAttached {
                                it.setToViewState(currentWeather, sortedForecast, timezone, address)
                                it.displayUvIndex(currentWeather)
                                it.displayUvIndexForecast(sortedForecast)
                                it.displayUserAddress(address)
                            }
                        }
                    }

                    override fun onFailGetForecast() {
                        ifViewAttached { it.displayGetUvIndexError() }
                    }
                })
    }

    fun onLocationReceived(latLng: LatLng) {
        ifViewAttached {
            it.stopLocationUpdates(QueryViewState.LocationSearchState.Idle)
        }

        getForecastUvIndex(latLng.latitude, latLng.longitude, null, null)
    }

    fun userClickedInstallButton() = ifViewAttached {
        it.displayInstallPrompt(Constants.RequestCodes.INSTALL_FROM_QUERY_FEATURE,
                Constants.ReferrerCodes.FROM_QUERY_FEATURE)
    }

    fun userClickedAboutButton() = ifViewAttached { it.displayAboutUi() }

    fun userClickedTextInputButton() = ifViewAttached { it.startPlacesAutoCompleteUi() }

    fun userDidNotWantToChangeLocationSettings() = ifViewAttached { it.displayCantDetectLocationError() }

    fun getPlaceAutoCompleteFailed() = ifViewAttached { it.displayGetAutoCompletePlaceError() }

    private fun getClosestWeather(weatherList: Collection<Weather>): Weather =
        Collections.min(weatherList) { w1, w2 ->
            val now = dateProvider.now()
            val diff1 = Math.abs(w1.datetime.time - now.time)
            val diff2 = Math.abs(w2.datetime.time - now.time)
                diff1.compareTo(diff2)
        }

}