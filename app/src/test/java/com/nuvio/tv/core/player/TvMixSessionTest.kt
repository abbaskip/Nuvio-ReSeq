package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TvMixSessionTest {
    private val meta = Meta("show", ContentType.SERIES, name = "Show", poster = null,
        posterShape = PosterShape.POSTER, background = null, logo = null, description = null,
        releaseInfo = null, imdbRating = null, genres = emptyList(), runtime = null, director = emptyList(),
        cast = emptyList(), videos = emptyList(), country = null, awards = null, language = null, links = emptyList())
    private val video = Video("show:1:1", "Episode", "2020-01-01", null, season = 1, episode = 1, overview = null)
    private val selection = TvMixSelection(meta, video)

    @Test fun `continuation belongs to exactly one profile show episode and launch`() {
        val session = TvMixSession()
        val first = TvMixSession.Playing(1, "mix", selection)
        session.playing.value = first
        assertTrue(session.matches(1, "show", video.id))
        assertFalse(session.matches(2, "show", video.id))
        assertFalse(session.matches(1, "other", video.id))
        assertFalse(session.matches(1, "show", "show:1:2"))
        session.end()
        session.playing.value = TvMixSession.Playing(1, "mix", selection)
        session.returnedExternally(true, true, first)
        assertNull(session.externalReturn.value)
        session.returnedExternally(true, false)
        assertFalse(session.externalReturn.value!!.autoplay)
        session.end()
        assertNull(session.playing.value)
        assertNull(session.externalReturn.value)
        session.returnedExternally(true, true)
        assertNull(session.externalReturn.value)
    }
}
