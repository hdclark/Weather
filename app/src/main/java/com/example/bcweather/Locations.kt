package com.example.bcweather

data class Location(val name: String, val lat: Double, val lon: Double)

object Locations {
    val all = listOf(
        Location("Surrey", 49.1913, -122.8490),
        Location("Maple Ridge", 49.2193, -122.5984),
        Location("Coquitlam", 49.2838, -122.7932),
        Location("North Vancouver", 49.3200, -123.0724),
        Location("Mission", 49.1329, -122.3262),
        Location("Chilliwack", 49.1579, -121.9515),
        Location("Hope", 49.3795, -121.4412),
        Location("Kelowna", 49.8880, -119.4960),
        Location("Whistler", 50.1163, -122.9574),
    )
}
