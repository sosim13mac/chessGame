package com.sosim.videocatcher.hls

/**
 * Resolves relative references against a base URL following RFC 3986 section 5.2,
 * the same way browsers do. Unlike java.net.URI it does not reject URLs with
 * characters that real servers often leave unescaped (`|`, spaces, `{}`...).
 */
object UrlResolver {

    // RFC 3986 appendix B.
    private val URI_REGEX = Regex("^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?$", RegexOption.DOT_MATCHES_ALL)

    private class Parts(
        val scheme: String?,
        val authority: String?,
        val path: String,
        val query: String?,
        val fragment: String?,
    )

    fun resolve(base: String, reference: String): String {
        val r = split(reference.trim())
        val scheme: String?
        val authority: String?
        val path: String
        val query: String?
        if (r.scheme != null) {
            scheme = r.scheme
            authority = r.authority
            path = removeDotSegments(r.path)
            query = r.query
        } else {
            val b = split(base.trim())
            scheme = b.scheme
            if (r.authority != null) {
                authority = r.authority
                path = removeDotSegments(r.path)
                query = r.query
            } else {
                authority = b.authority
                if (r.path.isEmpty()) {
                    path = b.path
                    query = r.query ?: b.query
                } else {
                    path = if (r.path.startsWith("/")) {
                        removeDotSegments(r.path)
                    } else {
                        removeDotSegments(merge(b, r.path))
                    }
                    query = r.query
                }
            }
        }
        return buildString {
            if (scheme != null) append(scheme).append(':')
            if (authority != null) append("//").append(authority)
            append(path)
            if (query != null) append('?').append(query)
            if (r.fragment != null) append('#').append(r.fragment)
        }
    }

    private fun split(uri: String): Parts {
        val m = URI_REGEX.matchEntire(uri) ?: return Parts(null, null, uri, null, null)
        return Parts(
            scheme = m.groups[2]?.value,
            authority = if (m.groups[3] != null) m.groups[4]?.value.orEmpty() else null,
            path = m.groups[5]?.value.orEmpty(),
            query = if (m.groups[6] != null) m.groups[7]?.value.orEmpty() else null,
            fragment = if (m.groups[8] != null) m.groups[9]?.value.orEmpty() else null,
        )
    }

    private fun merge(base: Parts, referencePath: String): String {
        if (base.authority != null && base.path.isEmpty()) return "/$referencePath"
        val slash = base.path.lastIndexOf('/')
        return if (slash >= 0) base.path.substring(0, slash + 1) + referencePath else referencePath
    }

    /** RFC 3986 section 5.2.4. */
    fun removeDotSegments(path: String): String {
        var input = path
        val output = StringBuilder()
        while (input.isNotEmpty()) {
            when {
                input.startsWith("../") -> input = input.substring(3)
                input.startsWith("./") -> input = input.substring(2)
                input.startsWith("/./") -> input = input.substring(2)
                input == "/." -> input = "/"
                input.startsWith("/../") -> {
                    input = input.substring(3)
                    removeLastSegment(output)
                }
                input == "/.." -> {
                    input = "/"
                    removeLastSegment(output)
                }
                input == "." || input == ".." -> input = ""
                else -> {
                    val next = input.indexOf('/', if (input.startsWith("/")) 1 else 0)
                    if (next < 0) {
                        output.append(input)
                        input = ""
                    } else {
                        output.append(input, 0, next)
                        input = input.substring(next)
                    }
                }
            }
        }
        return output.toString()
    }

    private fun removeLastSegment(output: StringBuilder) {
        val slash = output.lastIndexOf("/")
        output.setLength(if (slash >= 0) slash else 0)
    }
}
