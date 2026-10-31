package com.ratatoskr.android.autofill

import com.ratatoskr.shared.api.EntryOut

/**
 * What a fill or save request is for: the app asking, and, when the
 * fields are on a web page in a browser we trust, that page's domain.
 * `webDomain` is null for everything else, including WebViews inside
 * ordinary apps: an app controls its own WebView and could read whatever
 * gets filled into it, so a page's domain only counts when a real browser
 * reports it. Such apps are matched by package instead.
 */
data class AutofillTarget(val packageName: String, val webDomain: String?) {
    /** Key for remembered pairings (PairingStore): per site for web pages,
     * per app otherwise. */
    val pairingKey: String
        get() = if (webDomain != null) "web:${baseDomain(webDomain)}" else "app:$packageName"

    /** The `url` a new entry saved from here gets. `androidapp://` is the
     * convention other password managers (and their CSV exports) use for
     * apps, so these entries survive an import/export round trip. */
    val entryUrl: String
        get() = if (webDomain != null) "https://$webDomain" else "$APP_URL_SCHEME$packageName"

    companion object {
        fun of(packageName: String, webDomain: String?): AutofillTarget =
            AutofillTarget(packageName, webDomain?.takeIf { packageName in TRUSTED_BROWSERS }?.let(::normalizeHost))
    }
}

const val APP_URL_SCHEME = "androidapp://"

/** Browsers whose reported web domain is believed. Each passes autofill
 * the domain of the page actually showing, and none lets a page script
 * read another site's fields. */
val TRUSTED_BROWSERS = setOf(
    "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
    "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta",
    "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus",
    "com.microsoft.emmx", "com.brave.browser", "com.duckduckgo.mobile.android",
    "com.vivaldi.browser", "com.opera.browser", "com.kiwibrowser.browser",
)

/** Entries to suggest for `target`, best first: ones paired with it by
 * hand, then ones saved from this app, then ones whose URL is on the same
 * site. Nothing is guessed from names: a lookalike app shouldn't be
 * offered someone's bank password. */
fun matchingEntries(entries: List<EntryOut>, target: AutofillTarget, pairedIds: Set<String>): List<EntryOut> {
    val appUrl = APP_URL_SCHEME + target.packageName
    val siteBase = target.webDomain?.let(::baseDomain)
    return entries
        .mapNotNull { entry ->
            val rank = when {
                entry.id in pairedIds -> 0
                siteBase == null && entry.url.trim().equals(appUrl, ignoreCase = true) -> 1
                siteBase != null && hostOf(entry.url)?.let(::baseDomain) == siteBase -> 1
                else -> return@mapNotNull null
            }
            rank to entry
        }
        .sortedWith(compareBy({ it.first }, { it.second.site.lowercase() }))
        .map { it.second }
}

/** For the "Search Ratatoskr" picker only: entries that look related to
 * an app by name sort to the top, but the user still has to choose. */
fun looksRelated(entry: EntryOut, target: AutofillTarget): Boolean {
    val words = target.webDomain?.let { baseDomain(it).substringBefore('.') }?.let(::listOf)
        ?: target.packageName.lowercase().split('.').filter { it.length >= 4 && it !in GENERIC_PACKAGE_WORDS }
    val haystack = (entry.site + " " + entry.url).lowercase()
    return words.any { it in haystack }
}

private val GENERIC_PACKAGE_WORDS = setOf("android", "mobile", "app", "apps", "client", "release", "beta")

/** The host of an entry's URL, lowercased and without "www.", or null
 * for app entries and anything that isn't a URL. Lenient, because people
 * type "github.com" as often as "https://github.com/login". */
fun hostOf(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty() || trimmed.startsWith(APP_URL_SCHEME, ignoreCase = true)) return null
    val afterScheme = trimmed.substringAfter("://")
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
    val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    return host.takeIf { it.isNotEmpty() && ' ' !in it }?.let(::normalizeHost)
}

private fun normalizeHost(host: String): String = host.lowercase().trimEnd('.').removePrefix("www.")

/**
 * The registrable part of a host, so "accounts.google.com" and
 * "google.com" count as the same site. An approximation of the Public
 * Suffix List: the last two labels, or three under the common
 * second-level country suffixes ("bbc.co.uk"). IP addresses and
 * single-label names (localhost) only ever match themselves.
 */
fun baseDomain(host: String): String {
    val h = normalizeHost(host)
    if (h.startsWith("[") || h.all { it.isDigit() || it == '.' }) return h
    val labels = h.split('.')
    if (labels.size <= 2) return h
    val countrySecondLevel = labels.last().length == 2 && labels[labels.size - 2] in COUNTRY_SECOND_LEVEL
    return labels.takeLast(if (countrySecondLevel) 3 else 2).joinToString(".")
}

private val COUNTRY_SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "ac", "edu", "ltd", "plc", "ne", "or", "go")
