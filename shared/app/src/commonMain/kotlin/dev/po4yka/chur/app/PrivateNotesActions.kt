package dev.po4yka.chur.app

import dev.po4yka.chur.ffi.PrivateNoteSummary

/** Events from the pure private-notes screen. Storage remains in the session controller. */
interface PrivateNotesActions {
    fun refresh()
    fun search(terms: String)
    fun showTrash(trash: Boolean)
    fun nextPage()
    fun previousPage()
    fun create()
    fun open(note: PrivateNoteSummary)
    fun title(value: String)
    fun body(value: String)
    fun pinned(value: Boolean)
    fun back()
    fun dismissConfirmation()
    fun discardAndBack()
    fun save()
    fun saveAndBack()
    fun saveAndExport()
    fun export()
    fun requestTrash()
    fun requestPurge()
    fun trash()
    fun restore()
    fun purge()
    fun pin(note: PrivateNoteSummary)
}
