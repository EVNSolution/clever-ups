package com.evnsolution.ups.drivers

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/drivers")
class DriverController(private val store: DriverStore) {
    @GetMapping
    fun list(@RequestParam("centerId", required = false) centerId: String?): List<Driver> = store.findAll(centerId)
}
