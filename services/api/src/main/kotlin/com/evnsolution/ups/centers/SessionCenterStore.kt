package com.evnsolution.ups.centers

import com.evnsolution.ups.accounts.Role
import com.evnsolution.ups.accounts.SESSION_ATTRIBUTE
import com.evnsolution.ups.accounts.SessionInfo
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * The centers of the person behind the current request: the rows of `account_center` for that account.
 * The owner sees every center that any account has. The name comes from the `center` master when the center is
 * in it, and is the code otherwise.
 */
class SessionCenterStore(private val jdbc: JdbcClient) : CenterStore {
    override fun findAll(): List<Center> {
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        val session = request?.getAttribute(SESSION_ATTRIBUTE) as? SessionInfo ?: return emptyList()
        val codes = if (session.account.role == Role.OWNER) {
            jdbc.sql("SELECT DISTINCT center_code FROM account_center ORDER BY center_code")
                .query(String::class.java).list()
        } else {
            jdbc.sql("SELECT center_code FROM account_center WHERE account_id = :id ORDER BY center_code")
                .param("id", session.account.id).query(String::class.java).list()
        }
        val names = jdbc.sql("SELECT center_code, name_ko, depot_address_ko FROM center")
            .query { rs, _ ->
                Triple(rs.getString("center_code"), rs.getString("name_ko"), rs.getString("depot_address_ko"))
            }.list().associateBy { it.first }
        return codes.filterNotNull().map { code ->
            val master = names[code]
            Center(
                id = code,
                name = master?.second?.takeIf { it.isNotBlank() }?.let { "$code · $it" } ?: code,
                address = master?.third?.takeIf { it.isNotBlank() },
            )
        }
    }
}
