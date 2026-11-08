# Planned beta 9: Advanced Discover title filter

Advanced Discover adds **Title contains** for movies and TV shows. Enter part of
the displayed title, then select **Show results**. Matching ignores capitalisation
and surrounding spaces and combines with the other filters and selected sort.
Leaving it empty restores the existing discovery behaviour.

The field is saved with this profile's discoveries and cleared by Reset. Saved
discoveries from earlier versions remain readable.

Title matching applies as discovery pages load. **Matching titles loaded** counts
the visible matches; it is not a whole-catalogue total. **Load another page** keeps
looking even if the current page has no matches. The underlying API does not
support title text in Discover, so this retains provider filtering and sorting
instead of substituting a differently ranked search. Raw cached pages stay intact
when the text is edited or cleared.

Release with the next combined **beta 9**, alongside the Live TV Home fix, Stalker
recovery fix and latest NNTP implementation selected after the upstream work.
This feature branch does not change the app version or publish a release.

Before the combined release, verify the text editor, Done/Back and focus return
with the TV remote, plus touch input; apply, save/reopen, reset and load later
pages with and without matches. This feature's build/unit checks do not establish
device acceptance or replace the combined candidate's full publication gates.
