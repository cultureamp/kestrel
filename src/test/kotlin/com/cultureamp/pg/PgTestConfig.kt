package com.cultureamp.pg

import org.jetbrains.exposed.sql.Database

// H2 is the default databse. To use this, you need to input your database details
object PgTestConfig {
    val testerName = ""
    val jdbcUrl = "jdbc:postgresql://localhost:5432/${testerName}"
    val driver =  "org.postgresql.Driver"
    val user = testerName

    val db = if(jdbcUrl != "" && user != "") Database.connect(url = jdbcUrl, driver = driver, user = user) else null
}