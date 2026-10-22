package com.encrypz.privacy.drive

/** Follow every Drive page and reject repeated tokens rather than loop forever. */
internal object DrivePages {
    data class Page<T>(val items: List<T>, val nextToken: String?)
    fun <T> collect(load: (String?) -> Page<T>): List<T> {
        val items = mutableListOf<T>()
        val seen = mutableSetOf<String>()
        var token: String? = null
        do {
            val page = load(token)
            items.addAll(page.items)
            token = page.nextToken?.takeIf { it.isNotBlank() }
            check(token == null || seen.add(token)) { "Google Drive returned a repeated page token. Retry the download." }
        } while (token != null)
        return items
    }
}
