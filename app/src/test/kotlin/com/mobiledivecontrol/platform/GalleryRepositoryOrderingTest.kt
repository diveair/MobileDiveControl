package com.mobiledivecontrol.platform

import com.mobiledivecontrol.core.GalleryItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class GalleryRepositoryOrderingTest {

    @Test
    fun `most recently created album is pinned first without reordering the rest`() {
        val camera = album("Camera", "DCIM/Camera/")
        val screenshots = album("Screenshots", "Pictures/Screenshots/")
        val reefSurvey = album("Reef Survey", "DCIM/Reef Survey/")
        val albums = listOf(camera, screenshots, reefSurvey)

        val ordered = pinCreatedAlbumsFirst(albums, listOf("dcim/reef survey"))

        assertEquals(listOf(reefSurvey, camera, screenshots), ordered)
    }

    @Test
    fun `missing created album leaves repository order untouched`() {
        val albums = listOf(
            album("Camera", "DCIM/Camera/"),
            album("Screenshots", "Pictures/Screenshots/"),
        )

        assertSame(albums, pinCreatedAlbumsFirst(albums, listOf("DCIM/Not Present/")))
    }

    @Test
    fun `consecutively created albums remain pinned newest first`() {
        val camera = album("Camera", "DCIM/Camera/")
        val firstCreated = album("Dive Album 1", "DCIM/Dive Album 1/")
        val screenshots = album("Screenshots", "Pictures/Screenshots/")
        val secondCreated = album("Dive Album 2", "DCIM/Dive Album 2/")

        val ordered = pinCreatedAlbumsFirst(
            albums = listOf(camera, firstCreated, screenshots, secondCreated),
            createdRelativePathsNewestFirst = listOf(
                "DCIM/Dive Album 2/",
                "DCIM/Dive Album 1/",
            ),
        )

        assertEquals(listOf(secondCreated, firstCreated, camera, screenshots), ordered)
    }

    @Test
    fun `root media does not synthesize an Other album`() {
        assertNull(mediaFolderDisplayName(bucketDisplayName = null, relativePath = ""))
        assertNull(mediaFolderDisplayName(bucketDisplayName = "  ", relativePath = "/"))
    }

    @Test
    fun `missing bucket name falls back to the real relative folder`() {
        assertEquals(
            "Camera",
            mediaFolderDisplayName(bucketDisplayName = null, relativePath = "DCIM/Camera/"),
        )
    }

    private fun album(name: String, relativePath: String) = GalleryItem(
        id = relativePath.hashCode().toLong(),
        name = name,
        path = relativePath,
        relativePath = relativePath,
        isFolder = true,
    )
}
