package com.example.synopsis_navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.app.ActivityCompat
import com.example.synopsis_navigation.ui.theme.Synopsis_navigationTheme
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.IOException
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        val locationState = mutableStateOf(LatLng(0.0, 0.0))

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    locationState.value = LatLng(location.latitude, location.longitude)
                }
            }
        }

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
            ActivityResultCallback<Boolean> { granted ->
                if (granted) {
                    startLocationUpdates()
                } else {
                    // Handle denial
                }
            }
        )

        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            startLocationUpdates()
        }


        enableEdgeToEdge()
        setContent {
            Synopsis_navigationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SimpleMap(
                        lat = locationState.value.latitude,
                        lng = locationState.value.longitude,
                        Modifier.padding(innerPadding)
                    )

                }
            }
        }
    }

    fun getRouteFromAPI(
        origin: LatLng,
        destination: LatLng,
        apiKey: String,
        onResult: (List<LatLng>) -> Unit
    ) {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("maps.googleapis.com")
            .addPathSegments("maps/api/directions/json")
            .addQueryParameter("origin", "${origin.latitude},${origin.longitude}")
            .addQueryParameter("destination", "${destination.latitude},${destination.longitude}")
            .addQueryParameter("key", apiKey)
            .build()

        val request = Request.Builder().url(url).build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string()
                val points = parsePolylineFromJson(json)
                onResult(points)
            }
        })
    }

    data class RoutesRequest(
        val origin: Location,
        val destination: Location,
        @SerializedName("travelMode") val travelMode: String = "DRIVE",
        @SerializedName("routingPreference") val routingPreference: String = "TRAFFIC_AWARE",
        @SerializedName("computeAlternativeRoutes") val alternatives: Boolean = false,
        @SerializedName("languageCode") val language: String = "en-US",
        @SerializedName("units") val units: String = "METRIC"
    )

    data class Location(val location: LatLngWrapper)
    data class LatLngWrapper(@SerializedName("latLng") val latLng: LatLng)
    // Response classes
    data class RoutesResponse(val routes: List<Route>)
    data class Route(val polyline: PolylineData)
    data class PolylineData(@SerializedName("encodedPolyline") val encodedPolyline: String)

    fun getRouteFromRoutesAPI(
        origin: LatLng,
        destination: LatLng,
        apiKey: String,
        onResult: (List<LatLng>) -> Unit
    ) {
        val client = OkHttpClient()
        val url = "https://routes.googleapis.com/directions/v2:computeRoutes"

        val requestBody = RoutesRequest(
            origin = Location(LatLngWrapper(origin)),
            destination = Location(LatLngWrapper(destination))
        ).let { request ->
            Gson().toJson(request)
        }

        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .addHeader("X-Goog-Api-Key", apiKey)
            .addHeader("X-Goog-FieldMask", "routes.polyline.encodedPolyline") // Request only the polyline
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }
            override fun onResponse(call: Call, response: Response) {
            try {
                val json = response.body?.string()
                val routesResponse = Gson().fromJson(json, RoutesResponse::class.java)
                val polyline = routesResponse?.routes?.firstOrNull()?.polyline?.encodedPolyline
                onResult(if (polyline != null) decodePolyline(polyline) else emptyList())
            } catch (e: Exception) {
                onResult(emptyList())
            }
        }
        })
    }


    fun decodePolyline(encoded: String): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            // Latitude
            var shift = 0
            var result = 0
            do {
                val b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            // Longitude
            shift = 0
            result = 0
            do {
                val b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(LatLng(lat / 1E5, lng / 1E5))
        }
        return poly
    }

    fun parsePolylineFromJson(json: String?): List<LatLng> {
        val jsonObject = JSONObject(json)
        val routes = jsonObject.getJSONArray("routes")
        if (routes.length() == 0) return emptyList()
        val overview = routes.getJSONObject(0).getJSONObject("overview_polyline")
        val encoded = overview.getString("points")
        return decodePolyline(encoded)
    }


    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        }
    }


    @Composable
    fun SimpleMap(lat: Double, lng: Double, modifier: Modifier) {
        val location = LatLng(lat, lng)
        val defaultLocation = LatLng(50.073658, 14.418540)
        var routePoints by remember { mutableStateOf<List<LatLng>>(emptyList()) }
        val defaultCameraPosition = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom( defaultLocation,15f)
        }

        val destination = LatLng(50.087, 14.421) // hardcoded example destination

        LaunchedEffect(lat, lng) {
            val origin = LatLng(lat, lng)
            val apiKey = "AIzaSyCNQQa2iOjk7xXaIQ0pNbNlxdjDB6BrxbM"
            getRouteFromAPI(origin, destination, apiKey) { result ->
                routePoints = result
            }
        }

        GoogleMap(
            modifier = modifier.fillMaxSize(),
            cameraPositionState = defaultCameraPosition,
            googleMapOptionsFactory = {GoogleMapOptions().mapId(resources.getString(R.string.map_id))}

        ) {

            if (routePoints.isNotEmpty()) {
                Polyline(
                    points = routePoints,
                    color = Color.Blue,
                    width = 6f,
                    geodesic = true,
                    jointType = JointType.ROUND

                )
            }

            Marker(
                state = MarkerState(position = location),
                title = "You are here"
            )

        }
    }
}