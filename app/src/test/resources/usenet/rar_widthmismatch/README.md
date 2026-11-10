# RAR width-mismatch regression fixture

Fourteen original RAR5 volumes from `haaihond/NuvioTV`, commit
`bc6decd434c5718d4cb98e1ffddd9f1708bdd8b6`, directory
`native/usenet/testdata/rar_widthmismatch` (GPL-3.0, matching this project).

Source: https://github.com/haaihond/NuvioTV/tree/bc6decd434c5718d4cb98e1ffddd9f1708bdd8b6/native/usenet/testdata/rar_widthmismatch

These small stored archives exercise padded integer widths and Quick Open locators.
Original filenames are preserved, including `part010` through `part014`.
The member `payload_a.bin` is 200,000 bytes, byte `i` equal to `i % 256`.
Its SHA-256 is `c7a7d73b68d21102bf7d6d9be27b4106497efc8119224bebfbd26b375541bde7`.
No upstream executable or native code is included.
