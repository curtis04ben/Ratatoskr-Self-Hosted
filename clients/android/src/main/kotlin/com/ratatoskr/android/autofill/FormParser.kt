package com.ratatoskr.android.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId

/**
 * The login fields found on a screen, plus what they currently contain
 * (only meaningful for save requests, where it's what the user typed).
 * Either id may be null: some sign-ins ask for the username and the
 * password on separate screens.
 */
class LoginForm(
    val target: AutofillTarget,
    val usernameId: AutofillId?,
    val passwordId: AutofillId?,
    val usernameValue: String?,
    val passwordValue: String?,
) {
    val ids: Array<AutofillId> get() = listOfNotNull(usernameId, passwordId).toTypedArray()
}

/**
 * Finds the username and password fields in another app's view
 * structure. Most reliable signals first: the autofill hints an app
 * declares, then the field's input type (or HTML type, for web pages),
 * then words in its id or hint text. Returns null when there's no login
 * form, so the service stays out of the way of every other text field.
 */
object FormParser {
    fun parse(structure: AssistStructure): LoginForm? {
        val fields = mutableListOf<Field>()
        var webDomain: String? = null
        for (i in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(i).rootViewNode, fields) { domain -> if (webDomain == null) webDomain = domain }
        }

        val password = fields.firstOrNull { it.kind == Kind.PASSWORD }
        val username = fields.firstOrNull { it.kind == Kind.USERNAME }
            // Otherwise, the text field just before the password is
            // nearly always the username.
            ?: password?.let { pw -> fields.takeWhile { it !== pw }.lastOrNull { it.kind == Kind.OTHER_TEXT } }
        if (password == null && username == null) return null
        // A lone "username" guessed from a word in its id isn't enough
        // to pop up on, say, a search box: without a password field, it
        // has to be declared as a username or email.
        if (password == null && username?.declared != true) return null

        val target = AutofillTarget.of(structure.activityComponent.packageName, webDomain)
        return LoginForm(target, username?.id, password?.id, username?.value, password?.value)
    }

    private enum class Kind { USERNAME, PASSWORD, OTHER_TEXT }

    private class Field(val id: AutofillId, val kind: Kind, val declared: Boolean, val value: String?)

    private fun walk(node: AssistStructure.ViewNode, out: MutableList<Field>, onWebDomain: (String) -> Unit) {
        node.webDomain?.takeIf { it.isNotBlank() }?.let(onWebDomain)
        val id = node.autofillId
        if (id != null && node.autofillType == View.AUTOFILL_TYPE_TEXT && node.visibility == View.VISIBLE) {
            classify(node)?.let { (kind, declared) ->
                out += Field(id, kind, declared, node.autofillValue?.takeIf { it.isText }?.textValue?.toString())
            }
        }
        for (i in 0 until node.childCount) walk(node.getChildAt(i), out, onWebDomain)
    }

    /** The field's kind, and whether the app said so itself (hints/types)
     * rather than us guessing from its name. */
    private fun classify(node: AssistStructure.ViewNode): Pair<Kind, Boolean>? {
        val hints = node.autofillHints.orEmpty().map { it.lowercase() }
        if (hints.any { "password" in it }) return Kind.PASSWORD to true
        if (hints.any { it in USERNAME_HINTS || "username" in it || "email" in it }) return Kind.USERNAME to true
        // Fields an app marks as something else (an address, a one-time
        // code) are left alone.
        if (hints.isNotEmpty()) return null

        val htmlType = node.htmlInfo?.attributes?.firstOrNull { it.first.equals("type", ignoreCase = true) }?.second?.lowercase()
        if (htmlType == "password") return Kind.PASSWORD to true
        if (htmlType == "email") return Kind.USERNAME to true
        if (htmlType != null && htmlType != "text" && htmlType != "tel") return null

        val inputType = node.inputType
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_TEXT && variation in TEXT_PASSWORD_VARIATIONS) return Kind.PASSWORD to true
        if (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return Kind.PASSWORD to true
        if (cls == InputType.TYPE_CLASS_TEXT && variation in TEXT_EMAIL_VARIATIONS) return Kind.USERNAME to true

        val words = listOfNotNull(node.idEntry, node.hint, node.htmlInfo?.attributes?.firstOrNull { it.first == "name" }?.second)
            .joinToString(" ").lowercase()
        if (PASSWORD_WORDS.any { it in words }) return Kind.PASSWORD to false
        if (USERNAME_WORDS.any { it in words }) return Kind.USERNAME to false
        return if (cls == InputType.TYPE_CLASS_TEXT || cls == 0) Kind.OTHER_TEXT to false else null
    }

    private val USERNAME_HINTS = setOf(View.AUTOFILL_HINT_USERNAME, View.AUTOFILL_HINT_EMAIL_ADDRESS).map { it.lowercase() }.toSet()
    private val TEXT_PASSWORD_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
    )
    private val TEXT_EMAIL_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
    )
    private val PASSWORD_WORDS = listOf("password", "passwd", "passcode", "pwd")
    private val USERNAME_WORDS = listOf("username", "user_name", "userid", "user_id", "login", "email", "e-mail", "account")
}
