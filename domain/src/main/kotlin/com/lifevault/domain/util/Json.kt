package com.lifevault.domain.util

import kotlinx.serialization.json.Json

/** The single JSON configuration used for every vault file. */
val VaultJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}
