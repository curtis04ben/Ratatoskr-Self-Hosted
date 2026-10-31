package com.ratatoskr.android.autofill

import com.ratatoskr.shared.api.EntryOut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchingTest {
    private fun entry(id: String, site: String, url: String) = EntryOut(
        id = id, site = site, url = url, owner_username = "me", can_write = true, shared = false,
        created_at = 0.0, updated_at = 0.0,
    )

    private val github = entry("1", "GitHub", "https://github.com/login")
    private val google = entry("2", "Google", "accounts.google.com")
    private val bank = entry("3", "Bank", "https://bank.example")
    private val bankApp = entry("4", "Bank app", "androidapp://com.bank.mobile")
    private val all = listOf(github, google, bank, bankApp)

    @Test
    fun hostsAreParsedLeniently() {
        assertEquals("github.com", hostOf("https://www.GitHub.com/login?x=1"))
        assertEquals("github.com", hostOf("github.com"))
        assertEquals("192.168.1.5", hostOf("http://user@192.168.1.5:8000/"))
        assertNull(hostOf("androidapp://com.bank.mobile"))
        assertNull(hostOf(""))
        assertNull(hostOf("not a url"))
    }

    @Test
    fun baseDomainsGroupSubdomains() {
        assertEquals("google.com", baseDomain("accounts.google.com"))
        assertEquals("bbc.co.uk", baseDomain("www.news.bbc.co.uk"))
        assertEquals("localhost", baseDomain("localhost"))
        assertEquals("10.0.0.1", baseDomain("10.0.0.1"))
    }

    @Test
    fun browserPagesMatchBySite() {
        val chrome = AutofillTarget.of("com.android.chrome", "mail.google.com")
        assertEquals(listOf(google), matchingEntries(all, chrome, emptySet()))
        assertEquals(listOf(github), matchingEntries(all, AutofillTarget.of("org.mozilla.firefox", "github.com"), emptySet()))
    }

    @Test
    fun webDomainsFromOrdinaryAppsAreIgnored() {
        // An app's own WebView claiming to be github.com gets nothing.
        val target = AutofillTarget.of("com.evil.app", "github.com")
        assertNull(target.webDomain)
        assertTrue(matchingEntries(all, target, emptySet()).isEmpty())
    }

    @Test
    fun appsMatchBySavedPackageOrPairing() {
        val bankTarget = AutofillTarget.of("com.bank.mobile", null)
        assertEquals(listOf(bankApp), matchingEntries(all, bankTarget, emptySet()))
        // A hand-picked pairing comes first.
        assertEquals(listOf(bank, bankApp), matchingEntries(all, bankTarget, setOf("3")))
        // No guessing from names: a lookalike package matches nothing.
        assertTrue(matchingEntries(all, AutofillTarget.of("com.bank.mobile.fake", null), emptySet()).isEmpty())
    }

    @Test
    fun relatedEntriesOnlyGuideThePicker() {
        assertTrue(looksRelated(github, AutofillTarget.of("com.github.android", null)))
        assertFalse(looksRelated(bank, AutofillTarget.of("com.github.android", null)))
    }

    @Test
    fun newEntriesGetAUrlThatMatchesAgain() {
        val app = AutofillTarget.of("com.example.app", null)
        assertEquals("androidapp://com.example.app", app.entryUrl)
        assertEquals("https://example.org", AutofillTarget.of("com.android.chrome", "www.example.org").entryUrl)
        assertEquals("web:google.com", AutofillTarget.of("com.android.chrome", "mail.google.com").pairingKey)
    }
}
