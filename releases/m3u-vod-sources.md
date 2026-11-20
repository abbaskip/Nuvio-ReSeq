# M3U movies and series

M3U playlists can supply titles to On Demand and to the stream picker. Open **Settings → IPTV → On Demand** and enable the playlist. Existing and new M3U playlists start with movie and series import turned off. Their live channels and guide keep loading as before.

ReSeq can use a provider login in a get.php link, a login the provider confirms from its channel links, or movies and episodes listed in the playlist itself. If the same account is already enabled as an Xtream source, ReSeq keeps its existing position. The stream-source ordering screen lets you choose a different priority.

The existing catalogue stays at database version 11. The device keeps a separate, rebuildable index for playlist media links. The opt-in and category selections use the same backup fields as existing On Demand sources.

Allow about 85 MiB of device storage per 100,000 films listed directly in a playlist. The measured import of 98,000 films added 83.7 MiB across the catalogue and link index. Series, provider catalogues and the length of media links can change the total. Both switches that enable an M3U import show the estimate before you enable it.
