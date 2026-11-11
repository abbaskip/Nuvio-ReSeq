# Beta 9 lint reconciliation

All 16 inherited settings messages now have translations in every supported locale, including regional variants. Existing translations are retained and positional placeholders preserved.

AGP remains 8.13.2 with Gradle 8.13. The AGP 9 migration changes the Kotlin plugin, DSL/variant APIs, Gradle requirements and plugin compatibility. No measured beta 9 benefit justifies that migration in this release. Review it as a separate compatibility project. Official migration guidance: https://developer.android.com/build/releases/agp-9-0-0-release-notes

The existing verified-version-advice path now also handles AndroidGradlePluginVersion. It requires the checksum-verified immutable released XML, an existing warning, identical artifact and declared version, and unchanged source identity. Only the recommended available version may differ. Counts, severity, changed declarations, retired findings and other message changes remain blocking.

Release publication now runs the same full-debug lint report, guard and ratchet as upstream sync. Any budget reduction must be committed and the exact commit revalidated. The original beta 7.1 seed is retained. Summary output identifies inherited errors separately. New errors and worsening or returning findings remain blocking.
