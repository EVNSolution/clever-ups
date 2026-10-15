package com.evnsolution.ups.partner

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** A failing data source is answered with 502 and a fixed message. Nothing from the source is passed on. */
@RestControllerAdvice
class UpstreamErrors {
    @ExceptionHandler(Ours24Exception::class)
    fun ours24Failed(e: Ours24Exception): ResponseEntity<ProblemDetail> {
        log.error("Ours24 failed: {}{}", e.message, e.cause?.let { " (${it.javaClass.simpleName})" }.orEmpty())
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Ours24 연동에 실패했습니다."))
    }

    private companion object {
        val log = LoggerFactory.getLogger(UpstreamErrors::class.java)
    }
}
