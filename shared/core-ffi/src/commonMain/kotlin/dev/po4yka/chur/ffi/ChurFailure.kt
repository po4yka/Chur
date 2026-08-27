package dev.po4yka.chur.ffi

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.core.model.Retry

/**
 * A boundary failure, carrying the stable status and nothing private.
 *
 * `docs/ERROR_MODEL.md` keeps every message diagnostic-only and redacted, and
 * the C ABI carries only the code, so the `where` below is this side's constant
 * description of the call that failed. It never holds a filename, a password, a
 * search term, or any other value the caller supplied.
 */
class ChurFailure(val status: ChurStatus, val where: String) : Exception("${status.name}: $where") {
    /** Whether `docs/ERROR_MODEL.md` classifies this status as retryable. */
    val retryable: Boolean get() = status.retry == Retry.YES

    companion object {
        /** Turns a raw status value into a failure, or returns for success. */
        fun check(code: Int, where: String) {
            if (code == 0) return
            throw ChurFailure(ChurStatus.fromValue(code), where)
        }
    }
}
