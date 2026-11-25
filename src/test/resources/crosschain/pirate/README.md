# Public Pirate funding fixture

`musiqo-funding-9e20ff92.hex` contains the public, confirmed transaction
`9e20ff92f96a4609bfeabe559593445d04febfba46ad429862682c019a8f0087`, retrieved
read-only from `arrr3.qortal.link` and independently matched on `arrr.qortal.link`
on November 20, 2026. Both returned the same 2,529 bytes at block 4,220,443.

Output 0 contains 500,010,000 atomic units (5.0001 ARRR) and script
`a9148c99562ea007b0d503c1285a84794159c08eb49e87`. Its canonical script-hash
address is `bRYgz6zd6rSvP9SrDv5Yf49eewJT4A9edU`. Both servers' canonical
address history and UTXO queries were empty, while the public-key-hash index
alias `RN6cNPYRMLs1wJxgcRkaXvLyvKrsGKbXWa` returned this exact transaction
and still-unspent output. These are index lookup aliases for the test; the
contract's actual script and spending conditions do not change.

The fixture contains public transaction bytes, no wallet seeds or private keys.
Tests serve it over loopback and never contact a live wallet, sign or broadcast.
Other history entries in the tests are synthetic transparent prefixes for
checking the index merge, spend discovery and confirmation policies; they are
not valid signed transactions and do not establish live refund acceptance.
