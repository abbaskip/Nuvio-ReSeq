package com.nuvio.tv.data.tvdb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class TvdbEpisodeResponseTest {
    @Test fun `relative episode artwork resolves to TVDB artwork host and absolute URLs remain intact`() {
        for (path in listOf("/banners/episodes/73244/example.jpg", "banners/episodes/73244/example.jpg",
            "https://artworks.thetvdb.com/banners/episodes/73244/example.jpg")) {
            val episode = parseTvdbEpisode(Json.parseToJsonElement("""{"id":390588,"seasonNumber":5,"number":3,"image":"$path"}""").jsonObject)
            assertEquals("https://artworks.thetvdb.com/banners/episodes/73244/example.jpg", episode.image)
        }
    }

    @Test fun `Firefly DVD metadata preserves actual IDs and coordinates`() {
        val train = parseTvdbEpisode(Json.parseToJsonElement("""{"id":297989,"seasonNumber":1,"number":2,"name":"The Train Job","overview":"A train robbery","aired":"2002-09-20","runtime":45}""").jsonObject)
        assertEquals(297989L, train.id)
        assertEquals(2, train.coordinates.episode)
        assertEquals("The Train Job", train.title)
        assertEquals("A train robbery", train.overview)
        assertEquals(45, train.runtime)
    }

    @Test fun `specials retain season zero`() {
        val special = parseTvdbEpisode(Json.parseToJsonElement("""{"id":99,"seasonNumber":0,"number":1}""").jsonObject)
        assertEquals(0, special.coordinates.season)
    }

    @Test fun `missing identity or coordinates never silently become episode zero`() {
        listOf("""{"seasonNumber":1,"number":2}""", """{"id":297989,"number":2}""", """{"id":297989,"seasonNumber":1,"number":0}""").forEach { response ->
            try {
                parseTvdbEpisode(Json.parseToJsonElement(response).jsonObject)
                fail("Invalid response was accepted")
            } catch (e: TvdbException) { assertEquals(TvdbException.Reason.INVALID_RESPONSE, e.reason) }
        }
    }
}
