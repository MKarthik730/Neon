package com.lifevault.domain.util

import java.util.UUID

object Ids {
    fun new(): String = UUID.randomUUID().toString()
}
