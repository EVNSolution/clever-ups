package com.evnsolution.ups.centers

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** The centers of the person, each with the place of its depot when it is found. The place starts a route. */
@RestController
@RequestMapping("/api/centers")
class CenterController(private val store: CenterStore, private val depots: DepotPlaces) {
    @GetMapping
    fun list(): List<Center> =
        store.findAll().map { center ->
            val found = depots.of(center.address)
            if (found == null) center else center.copy(lng = found.lng, lat = found.lat)
        }
}
