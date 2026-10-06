package com.tracel.tests.support

import com.tracel.engine.log.lookup.NameMatcher

object NamespacedNames : NameMatcher {
    override fun matches(stored: String, wanted: String): Boolean {
        val end = stored.indexOf('[')
        val storedEnd = if (end < 0) stored.length else end
        if (storedEnd == wanted.length && stored.regionMatches(0, wanted, 0, storedEnd, ignoreCase = true)) return true
        val storedFrom = stored.indexOf(':').let { if (it in 0 until storedEnd) it + 1 else 0 }
        val wantedFrom = wanted.indexOf(':') + 1
        val storedLength = storedEnd - storedFrom
        return storedLength == wanted.length - wantedFrom &&
            stored.regionMatches(storedFrom, wanted, wantedFrom, storedLength, ignoreCase = true)
    }
}
