/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

/**
 * Removes secrets and device-unique values from text people may post in an issue: the log and the
 * diagnostics file. The panel's code already never logs them; this is the second net.
 *
 * Removed: HA tokens (JWTs, `Bearer …`), `key=value` pairs whose name says secret (token, password, psk,
 * key, secret, dataset, tlv), base64 32-byte keys (the ESPHome API key), long hex (Thread datasets and
 * network keys, EUI64s, extended PAN ids, serials), and MAC addresses.
 */
object Redact {
    private val rules: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("eyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]+") to { _ -> "<token>" },
        Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+") to { _ -> "Bearer <token>" },
        Regex("(?i)\\b([a-z_]*(?:token|password|passwd|psk|secret|api_?key|network_?key|noise_?key|key|dataset|tlv|code_verifier))(\"?\\s*[:=]\\s*\"?)([^\\s\"',&}]+)") to
            { m -> m.groupValues[1] + m.groupValues[2] + "<removed>" },
        Regex("(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{43}=") to { _ -> "<key>" },
        Regex("\\b(?:[0-9A-Fa-f]{2}[:-]){7}[0-9A-Fa-f]{2}\\b") to { _ -> "<eui64>" },
        Regex("\\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b") to { _ -> "<mac>" },
        Regex("\\b[0-9A-Fa-f]{16,}\\b") to { _ -> "<hex>" },
    )

    fun text(s: String): String = rules.fold(s) { acc, (re, to) -> re.replace(acc, to) }
}
