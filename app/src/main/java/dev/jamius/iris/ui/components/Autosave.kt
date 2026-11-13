/*
* Author: Jamius Siam
* Since: 13/11/2026
*/

package dev.jamius.iris.ui.components

/** What committing a text field's draft does. */
sealed interface Commit {
    /** The trimmed draft equals the stored value. */
    data object None : Commit

    data class Save(val value: String) : Commit

    /** Not saved; [error] goes in the field's helper slot. */
    data class Invalid(val error: String) : Commit
}

/** Trims [draft]: unchanged from [stored] = [Commit.None], else [validate] (null = valid) decides. */
fun commitDecision(draft: String, stored: String, validate: (String) -> String?): Commit {
    val trimmed = draft.trim()
    if (trimmed == stored) return Commit.None
    validate(trimmed)?.let { return Commit.Invalid(it) }
    return Commit.Save(trimmed)
}
