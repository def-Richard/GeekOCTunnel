# Desktop contract draft v1

This directory shares data definitions and behavioral examples with Android; it is
not a cross-language binary ABI. Android's persisted profile format is unchanged.

`config/profiles-v1.schema.json` describes the initial portable profile container.
The C++ parser additionally enforces a 256 KiB document limit, unique IDs, trimmed
nonempty names without control characters, and HTTPS server-address semantics.
All unknown fields and schema versions are rejected before saving. Profile order
is preserved. Optional `enableUdp` is a desktop transport preference: absent means
false so existing configurations keep their TCP behavior. Older previews that do
not recognize this field cannot import the new export. Global certificate bypass
is local application policy and never part of an exported server trust decision.

`fixtures/server-addresses.tsv` is one shared golden file consumed by C++ Qt Test
and Android JVM tests. Each noncomment line has input and normalized output separated
by a tab; `!` means reject. The format needs no additional Kotlin JSON dependency.
All examples use reserved documentation hosts/addresses. The fixtures cover URL
components, port limits, bracketed IPv6, ambiguous IPv4 and hostname normalization.
They establish this tested subset, not complete cross-language parser equivalence.

Authentication, session state, privileged IPC, multi-tunnel routes and packet
translation fixtures will be added when their implementations are introduced.
Do not add credentials, real organization servers, private topology or device paths here.
