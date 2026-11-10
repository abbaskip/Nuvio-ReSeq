# Sports coverage audit — 10 November 2026

The existing 13 curated taxonomy branches, including every ID, label, alias and ordering, are unchanged. New coverage is appended; saved team IDs, follows, row ordering and exclusions remain supported. An existing `legacy/RACING` exclusion still disables Motorsport after its new taxonomy branch is added.

Provider checks:

- [ESPN sport index](https://sports.core.api.espn.com/v2/sports?limit=1000): all 17 indexed sports now have a scoreboard-backed catalogue entry. Added cricket, field hockey and water polo, plus men's college volleyball.
- [ESPN Test scoreboard](https://site.api.espn.com/apis/site/v2/sports/cricket/19430/scoreboard?dates=20261110): event `1550711`, Pakistan v Sri Lanka, first Test, supplies live innings and day/session status. The parser retains the end date and shows an available innings before the opposition bats. Cricket refreshes use UTC and the current match day, rather than US Eastern's previous calendar date. The original kickoff remains the event's timeline identity.
- [TheSportsDB sport browser](https://www.thesportsdb.com/sport/leagues): discovery now covers all 37 listed categories, along with existing app sources such as Swimming, Horse Racing, Pool and Billiards. The free `all_sports.php` and league discovery responses are partial, so they cannot be treated as a complete audit.
- [TheSportsDB Motorsport browser](https://www.thesportsdb.com/sport/motorsport): 38 active competitions are seeded locally, including Supercars (`4489`, with V8 Supercars aliases), Formula 2 (`4486`) and Formula 3 (`4487`). They are browseable/followable before network discovery. All Motorsport includes its championships. The app and public catalogue producer share `sports-provider-coverage.json`; previously discovered leagues continue to refresh when a later discovery response omits them.

More Sports gains Esports, Handball, Field Hockey, Badminton, Climbing, Equestrian, Shooting, Extreme Sports, Table Tennis, Multi Sports, Watersports, Weightlifting, Skiing, Skating, Winter Sports, Poker, Lawn Bowls, Cornhole and Ten Pin Bowling.

Guide coverage for the last three works independently of provider fixtures. For example, `Bowls Premier League: BPL 24 day 1` belongs in TV Now during its programme window. Explicit live wording in the original guide title/description produces LIVE; otherwise the airing remains ON AIR. Replays never establish live play. Recognised long cricket, bowls and endurance sessions are no longer rejected solely for exceeding six hours.

Regression coverage includes the captured ESPN match response, a day-two Fox Sports 505 session, Fox Sports 506 bowls coverage, live/unknown airing labels, follow membership, independent exclusions, the original taxonomy, and producer retention of partial source data. The captured score is a test fixture, not a value displayed by the app.

These are source changes. The app update and updated public catalogue must be released for installed devices to receive the full coverage. No APK was installed as part of this audit.

## Beta 9.3 preparation

At the maintainer's request, beta 9.2 was returned to draft on 10 November 2026. Its seven release assets and source tag are retained; the public latest-release endpoint now returns beta 9.1. The intended base for beta 9.3 is the exact withdrawn beta 9.2 commit, `f995e772eb92c8a6add819d61676f195fbfc5a41`.

Branch `feature/beta9.3-sports` combines these sports fixes with that base, including the production guide-matching optimizations and score-alert baseline logic. The completed continue-watching shuffle and manual-source-selection fixes are integrated. The maintainer explicitly authorized beta 9.3 publication after the combined validation gates pass; the hold is cleared and version code 10041 is reserved. No APK was installed on a physical device.
