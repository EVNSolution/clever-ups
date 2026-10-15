package com.evnsolution.ups.driverapp

/** One UPS delivery code: KB is delivered; every other code is an exception the driver may report. */
data class UpsCode(val code: String, val category: String, val short: String, val korean: String?)

/**
 * The UPS delivery codes, from the Codes sheet of the UPS file "배송 POD 및 센터 코드.xlsx" (UPS Exception list,
 * 41 rows, read 2026-10-14). Codes and wording are kept as UPS writes them; the Korean is the sheet's own, when given.
 */
object UpsCodes {
    const val DELIVERED = "KB"

    val all: List<UpsCode> = listOf(
        UpsCode("48", "Not In", "NOT IN 1", "담당자 부재(1차)"),
        UpsCode("KX", "Not In", "NOT IN 2", "담당자 부재(2차)"),
        UpsCode("49", "Not In", "NOT IN 3", "담당자 부재(3차)"),
        UpsCode("AX", "Holiday/ Not Available", "VACATION", "수취인 휴가"),
        UpsCode("G7", "No Adult", "NO ADULT SIG 1", null),
        UpsCode("FM", "No Adult", "NO ADULT SIG 2", null),
        UpsCode("G8", "No Adult", "NO ADULT SIG 3", null),
        UpsCode("AM", "Refused", "REF CANCELED", null),
        UpsCode("AN", "Refused", "REF DUPLICAT", null),
        UpsCode("AP", "Refused", "REF NO INVCE", null),
        UpsCode("AR", "Refused", "REF TOO $$$", null),
        UpsCode("AS", "Refused", "REF TOO LATE", null),
        UpsCode("KI", "Refused", "REFUSED PAY", "COD 지불 거절"),
        UpsCode("FE", "Refused", "REF DUTY/VAT", null),
        UpsCode("CS", "SHIPPER RTS", "SHIPPER RETURN", null),
        UpsCode("I1", "SHIPPER RTS", "RETURNING TO SHIPPER", null),
        UpsCode("KB", "Delivered", "DELIVERED", "배송완료"),
        UpsCode("DJ", "Miscellaneous", "WEATHER DLAY", "기상악화(폭설,폭우)"),
        UpsCode("CV", "Holiday/ Not Available", "OVERWEIGHT", null),
        UpsCode("MF", "Holiday/ Not Available", "FUTURE", "수취인 추후 배달 요청"),
        UpsCode("C8", "Holiday/ Not Available", "OUT/BUSINESS", "수취인 사업장 폐업"),
        UpsCode("UW", "UNPAID COD", "UNPAID COD", null),
        UpsCode("DW", "Holiday/ Not Available", "VACANT", "수취인 휴가"),
        UpsCode("HA", "Miscellaneous", "ACCIDENT", null),
        UpsCode("51", "No Money", "NO MONEY 1", "No Money(1차)"),
        UpsCode("KZ", "No Money", "NO MONEY 2", "No Money(2차)"),
        UpsCode("52", "No Money", "NO MONEY 3", "No Money(3차)"),
        UpsCode("A7", "Holiday/ Not Available", "MOVED", "사무실 이전"),
        UpsCode("45", "Damage", "DAMAGE", null),
        UpsCode("G3", "Closed", "CLOSED 1", "사무실 문닫힘(1차)"),
        UpsCode("L1", "Closed", "CLOSED 2", "사무실 문닫힘(2차)"),
        UpsCode("AY", "Closed", "CLOSED 3", "사무실 문닫힘(3차)"),
        UpsCode("47", "Holiday/ Not Available", "OUT/TERRITORY", null),
        UpsCode("A8", "Incomplete Address", "NEED APT #", "아파트 동,호수 필요"),
        UpsCode("AA", "Incomplete Address", "NEED SUITE #", "빌딩 호수 필요"),
        UpsCode("AC", "Incomplete Address", "INCORR CITY", "부정확한 도시명"),
        UpsCode("AD", "Incomplete Address", "NO SUCH REC", "회사이름/담당자명 필요"),
        UpsCode("AE", "Incomplete Address", "NO SUCH #", "세부주소(거리명) 필요"),
        UpsCode("AF", "Incomplete Address", "NO SUCH STRT", "세부주소(번지수) 필요"),
        UpsCode("30", "REQ CANCELED", "REQUEST CANCELLED", null),
        UpsCode("UM", "CLAIM INVEST", "CLAIM INVESTIGATION", null),
    )

    private val known = all.map { it.code }.toSet()

    fun isException(code: String): Boolean = code in known && code != DELIVERED
}
