# IPTV favourites and ordering

## Remote interaction

Long-press a regular group to add/remove it from favourite groups or open **Manage group order**. Favourite groups have a star and appear before ordinary groups, below the built-in navigation rows. They appear once, retain their playlist identity and locks, and remain accessible when their playlist is collapsed. Hiding a favourite hides it; unhiding restores its favourite status. Favourites are separate from favourite channels and saved per profile. As with existing IPTV preferences, a new profile initially receives a copy of the first profile, then changes independently.

After a successful Xtream Live TV refresh, Playlist order follows the provider's live category list. In Custom order, saved moves take precedence and remaining groups follow the provider. Groups missing from the category list keep their relative channel-list order after the ranked groups.

The group editor contains only the selected playlist (custom groups share a separate scope). The channel editor contains only the current group. Search and Recently watched cannot be reordered. Favourite pinning takes precedence over playlist order: moving a favourite to the bottom does not unpin it.

- Up/Down normally navigates.
- OK picks up one row. Up/Down moves it; OK puts it down.
- Hold OK selects a row and enters multiple-selection mode. Subsequent OK presses or holds toggle rows. The selected count and checkmarks remain visible.
- Right opens the always-visible move controls. Move up/down gathers the selected rows into a contiguous block just before the previous neighbour / after the next neighbour. Their list order is preserved, regardless of selection order.
- Move to top/bottom places that block at the scope boundary. Unselected rows retain their relative order. No movement wraps around or crosses a playlist/group boundary.
- Select multiple is also available as an explicit action for remotes without reliable long-press support. Clear selection exits that mode.
- Save commits the complete draft. Cancel or Back discards it. Leaving a picked-up row via Left/Right retains its selection for the move controls.

When displayed alphabetically, entering management or using a direct move first shows **Replace custom order?**, with Cancel focused. Cancel leaves the saved custom order and sort mode untouched. Continue replaces the saved custom baseline with the alphabetical list and switches to custom sorting before editing. Cancelling the subsequent editor discards only its draft moves; the explicitly accepted alphabetical baseline remains. For channels, custom sorting is a per-group override, so other groups keep their sort setting.

The context menu also offers direct Move to top/bottom for one group or channel. These commit immediately. Group actions affect only that playlist; channel actions affect only the current group, including All channels, favourite channels, and custom groups. A manual channel order takes precedence over alphabetical/number sorting only for the edited group. New channels follow the saved entries in the configured sort order.

## Persistence and refresh

Favourite group IDs are profile-scoped DataStore preferences, using existing profile backup/export handling. Group order retains IDs absent from the current visible list. Channel favourites and custom-group membership remain intact on reorder. Stale favourite IDs have no visible effect until their group is available again. The draft is a snapshot; newly imported entries not in the snapshot follow saved entries. No provider playlist or stream URL is modified.

## Validation

Unit tests exercise all 64 subsets of a six-row list in every direction, stable block order, empty/stale selection, boundaries, and playlist isolation. DataStore tests cover favourite toggling/profile isolation and preservation of unavailable group IDs. Compose tests cover remote pickup/movement/save, cancellation, multi-selection and movement controls. Device execution status is recorded in the task delivery, separately from compilation.

Manual TV acceptance: use two playlists with duplicate group names, collapse one, favourite a locked group, hide/unhide it, and switch profiles/restart. Move non-adjacent groups, a first/last row, every row, and channels in provider/custom/favourite groups; repeat with name sorting. Verify held OK does not also trigger a click, scrolling retains focus, Cancel leaves persisted order unchanged, and playback does not start during management.

### Results — 5 November 2026

- Full IPTV JVM regression run: 325 tests passed.
- Final affected JVM tests (including collapsed-playlist rows in both guide and overlay): 10 passed.
- Onn device instrumentation: 7 passed (6 ordering/confirmation UI tests plus packaged backend/TMDB configuration).
- Debug APK and instrumentation APK verified from their actual DEX contents before installation; backend values withheld. The compatible armeabi-v7a debug package was installed, leaving the release package separate.
- Visual review of `app/build/reports/iptv-order-selection.png`: controls and selected rows fit the screen; selected rows form the expected stable block.
- Installed debug app sign-in generated a QR and pairing code on the Onn box.

Reports are under `app/build/reports/ordering-*`. Provider-specific refresh scenarios in the manual checklist above were not exercised against a live subscription.
