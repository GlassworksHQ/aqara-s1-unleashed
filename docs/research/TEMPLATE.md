# <Topic>: <one-line question this note answers>

**Status (YYYY-MM-DD): working | partly working | dead end | untested.** One or two sentences: what
works now and what doesn't.

Device: <model>, firmware <version>. Tools: <names and versions>.

## Question

What we wanted to know or make work, and why it matters for the project.

## What we tried

For each attempt, in order, including the ones that failed:

1. **<attempt>** (YYYY-MM-DD, verified | read | untested)
   ```
   $ exact command
   output, redacted
   ```
   What it showed, and what it rules out.

## Result

The answer, in a sentence or a table. Put each claim next to its evidence: a command above, or a
source file and line, or a binary and offset.

## How to reproduce

The steps from a known state, with the scripts in `tools/` they use. Say what each step touches:
RAM only, read-only, or writes storage. Say how to undo it.

## Open questions

What is still unknown, and what the next test would be.

<!--
Before committing:
- No serials, MACs, EUI64s, keys, PAN IDs, tokens or Wi-Fi details. Redact them in logs.
- No vendor binaries or firmware, only names, versions, offsets and what you read there.
- Add this note to the table in docs/research/README.md.
-->
