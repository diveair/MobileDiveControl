package com.mobiledivecontrol.platform

import com.mobiledivecontrol.core.GalleryItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class GalleryRepositoryOrderingTest {

    @Test
    fun `most recently created album is pinned first without reordering the rest`() {
        val camera = album("Camera", "DCIM/Camera/")
        val screenshots = album("Screenshots", "Pictures/Screenshots/")
        val reefSurvey = album("Reef Survey", "DCIM/Reef Survey/")
        val albums = listOf(camera, screenshots, reefSurvey)

        val ordered = pinCreatedAlbumFirst(albums, "dcim/reef survey")

        assertEquals(listOf(reefSurvey, camera, screenshots), ordered)
    }

    @Test
    fun `missing created album leaves repository order untouched`() {
        val albums = listOf(
            album("Camera", "DCIM/Camera/"),
            album("Screenshots", "Pictures/Screenshots/"),
        )

        assertSame(albums, pinCreatedAlbumFirst(albums, "DCIM/Not Present/"))
    }

    private fun album(name: String, relativePath: String) = GalleryItem(
        id = relativePath.hashCode().toLong(),
        name = name,
        path = relativePath,
        relativePath = relativePath,
        isFolder = true,
    )
}
