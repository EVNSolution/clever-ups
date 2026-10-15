package com.evnsolution.ups.routes

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/routes")
class RouteController(private val store: RouteStore) {
    @GetMapping
    fun list(@RequestParam("centerId", required = false) centerId: String?): List<Route> = store.findAll(centerId)
}
