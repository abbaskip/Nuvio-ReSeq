# ReSeq Mobile attribution

ReSeq Mobile adapts touch UI from **NuvioPlusIPTV-Mobile** by **homelessbrian and contributors**, based on **NuvioMedia/NuvioMobile** and the original phone port by **ghbarker**. The source revision used for this work is [cbd3d8575217d85ff251d831b6cf72b90c1e4b4d](https://github.com/homelessbrian/NuvioPlusIPTV-Mobile/tree/cbd3d8575217d85ff251d831b6cf72b90c1e4b4d). The upstream work and these adaptations are distributed under the GNU General Public License version 3. See [LICENSE](LICENSE).

The reference files under `composeApp/src/commonMain/kotlin/com/nuvio/app` are:

- `MainTabsDestination.kt` for touch tab navigation.
- `features/livetv/ui/LiveTvGuideScreen.kt` for the scrolling timeline, channel column, group strip and programme actions.
- `features/livetv/ui/LiveTvPlayerOverlay.kt` for channel swipes and the portrait, tablet and landscape channel panel.
- `features/livetv/ui/CalendarScreen.kt` for the week strip, month grid, selected-day agenda and entry sheet.
- `features/livetv/ui/sports/SportsScreen.kt` and `SportsUi.kt` for the My Sports rows, dated All Events list and fixture cards.

The ReSeq adaptation uses Android Compose, ReSeq theme surfaces and condensed type. The screens call ReSeq's profile-owned ViewModels, repositories and player. Existing component notices in this project continue to apply.
