package com.mobiledivecontrol.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GalleryMenuFlowTest {
    private val reducer = ControlReducer()

    @Test
    fun `touch gallery shortcut opens internal gallery from every camera mode`() {
        CameraModeId.entries.forEach { mode ->
            val state = AppState(camera = CameraState(activeMode = mode))
            val result = reducer.reduce(state, CameraCommand.OpenGallery)
            assertEquals(AppMode.Gallery, result.state.mode, mode.name)
            assertEquals(GalleryViewMode.Browser, result.state.gallery.viewMode)
            assertEquals(listOf(PlatformEffect.LoadGalleryItems), result.effects)
            assertEquals(state.camera, result.state.camera)
        }
    }

    @Test
    fun `an empty album grid defaults to Back and vertical navigation cannot clear it`() {
        val loaded = reducer.reduce(
            AppState(
                mode = AppMode.Gallery,
                gallery = GalleryState(
                    items = listOf(
                        GalleryItem(1, "Camera", "bucket-camera", isFolder = true),
                    ),
                ),
            ),
            GalleryCommand.LoadItems(emptyList()),
        )

        assertTrue(loaded.state.gallery.browserBackFocused)
        assertEquals(GalleryBrowserAction.Back, loaded.state.gallery.browserAction)

        val stillBack = reducer.reduce(loaded.state, GalleryCommand.NavigateDown)
        assertTrue(stillBack.state.gallery.browserBackFocused)
        assertEquals(GalleryBrowserAction.Back, stillBack.state.gallery.browserAction)
    }

    @Test
    fun `an empty inner-album grid defaults to Back in every direction`() {
        val album = GalleryItem(1, "Empty Album", "bucket-empty", isFolder = true)
        val opening = reducer.reduce(
            AppState(
                mode = AppMode.Gallery,
                gallery = GalleryState(
                    viewMode = GalleryViewMode.AlbumActions,
                    items = listOf(album),
                ),
            ),
            GalleryCommand.Confirm,
        )
        val loaded = reducer.reduce(opening.state, GalleryCommand.LoadItems(emptyList()))

        listOf(
            GalleryCommand.NavigateUp,
            GalleryCommand.NavigateDown,
            GalleryCommand.NavigateLeft,
            GalleryCommand.NavigateRight,
        ).forEach { command ->
            val navigated = reducer.reduce(loaded.state, command)
            assertTrue(navigated.state.gallery.browserBackFocused)
            assertEquals(GalleryBrowserAction.Back, navigated.state.gallery.browserAction)
        }
    }

    @Test
    fun `loading a non-empty grid focuses its first item unless Back was explicitly preserved`() {
        val item = GalleryItem(1, "Camera", "bucket-camera", isFolder = true)
        val initialLoad = reducer.reduce(
            AppState(mode = AppMode.Gallery),
            GalleryCommand.LoadItems(listOf(item)),
        )
        assertFalse(initialLoad.state.gallery.browserBackFocused)
        assertNull(initialLoad.state.gallery.browserAction)
        assertEquals(0, initialLoad.state.gallery.selectedIndex)

        val explicitBack = AppState(
            mode = AppMode.Gallery,
            gallery = GalleryState(
                browserBackFocused = true,
                browserAction = GalleryBrowserAction.Back,
            ),
        )
        val preserved = reducer.reduce(explicitBack, GalleryCommand.LoadItems(listOf(item)))
        assertTrue(preserved.state.gallery.browserBackFocused)
        assertEquals(GalleryBrowserAction.Back, preserved.state.gallery.browserAction)
    }

    @Test
    fun `albums open into a mixed media grid and media opens full screen`() {
        val album = GalleryItem(
            id = -1,
            name = "Camera",
            path = "bucket-camera",
            isFolder = true,
            mediaCount = 2,
        )
        val albumState = AppState(
            mode = AppMode.Gallery,
            gallery = GalleryState(items = listOf(album)),
        )

        val albumActions = reducer.reduce(albumState, GalleryCommand.Confirm)
        assertEquals(GalleryViewMode.AlbumActions, albumActions.state.gallery.viewMode)
        assertEquals(GalleryAlbumAction.Preview, albumActions.state.gallery.albumAction)
        assertTrue(albumActions.effects.isEmpty())

        val openedAlbum = reducer.reduce(albumActions.state, GalleryCommand.Confirm)
        assertEquals("bucket-camera", openedAlbum.state.gallery.currentFolder)
        assertEquals("Camera", openedAlbum.state.gallery.currentFolderName)
        assertEquals(listOf(PlatformEffect.LoadGalleryItems), openedAlbum.effects)

        val media = listOf(
            GalleryItem(1, "still.jpg", "DCIM/Camera/still.jpg", albumId = "bucket-camera"),
            GalleryItem(2, "clip.mp4", "DCIM/Camera/clip.mp4", albumId = "bucket-camera", isVideo = true),
        )
        val loaded = reducer.reduce(openedAlbum.state, GalleryCommand.LoadItems(media))
        val mediaActions = reducer.reduce(loaded.state, GalleryCommand.OpenItem(1))
        assertEquals(GalleryViewMode.MediaActions, mediaActions.state.gallery.viewMode)
        assertEquals(GalleryMediaAction.Preview, mediaActions.state.gallery.mediaAction)
        val openedMedia = reducer.reduce(mediaActions.state, GalleryCommand.Confirm)

        assertEquals(GalleryViewMode.Preview, openedMedia.state.gallery.viewMode)
        assertEquals(1, openedMedia.state.gallery.selectedIndex)
        assertEquals(GalleryPreviewAction.PlayPause, openedMedia.state.gallery.previewAction)
        assertFalse(openedMedia.state.gallery.videoPlaying)

        val photoActions = reducer.reduce(loaded.state, GalleryCommand.OpenItem(0))
        assertEquals(GalleryViewMode.MediaActions, photoActions.state.gallery.viewMode)
        assertEquals(GalleryMediaAction.Preview, photoActions.state.gallery.mediaAction)
        val openedPhoto = reducer.reduce(photoActions.state, GalleryCommand.Confirm)
        assertEquals(GalleryPreviewAction.Next, openedPhoto.state.gallery.previewAction)
        assertFalse(galleryPreviewRailActions(isVideo = false).contains(GalleryPreviewAction.PlayPause))
    }

    @Test
    fun `photo and video preview rails keep delete after details at far right`() {
        val state = previewState()
        assertEquals(
            listOf(
                GalleryPreviewAction.Back,
                GalleryPreviewAction.Options,
                GalleryPreviewAction.Previous,
                GalleryPreviewAction.PlayPause,
                GalleryPreviewAction.Next,
                GalleryPreviewAction.Details,
                GalleryPreviewAction.Delete,
            ),
            galleryPreviewRailActions(isVideo = true),
        )
        assertEquals(
            listOf(
                GalleryPreviewAction.Back,
                GalleryPreviewAction.Options,
                GalleryPreviewAction.Previous,
                GalleryPreviewAction.Next,
                GalleryPreviewAction.Details,
                GalleryPreviewAction.Delete,
            ),
            galleryPreviewRailActions(isVideo = false),
        )
        assertEquals(
            listOf(
            GalleryPreviewAction.Delete,
            GalleryPreviewAction.Back,
            GalleryPreviewAction.Options,
            GalleryPreviewAction.Previous,
            GalleryPreviewAction.PlayPause,
            GalleryPreviewAction.Next,
            GalleryPreviewAction.Details,
            ),
            GalleryPreviewAction.entries,
        )

        var current = state
        repeat(4) { current = reducer.reduce(current, GalleryCommand.NavigateLeft).state }
        assertEquals(GalleryPreviewAction.Back, current.gallery.previewAction)
        assertEquals(
            GalleryPreviewAction.Back,
            reducer.reduce(current, GalleryCommand.NavigateLeft).state.gallery.previewAction,
        )

        val back = state.copy(gallery = state.gallery.copy(previewAction = GalleryPreviewAction.Back))
        assertEquals(
            GalleryPreviewAction.Options,
            reducer.reduce(back, GalleryCommand.NavigateRight).state.gallery.previewAction,
        )
        assertEquals(
            GalleryPreviewAction.Back,
            reducer.reduce(back, GalleryCommand.NavigateUp).state.gallery.previewAction,
        )

        current = state
        repeat(2) { current = reducer.reduce(current, GalleryCommand.NavigateRight).state }
        assertEquals(GalleryPreviewAction.Details, current.gallery.previewAction)
    }

    @Test
    fun `open details consume vertical housing input and keep every metadata row reachable`() {
        val lines = (1..12).map { "Detail $it" }
        val open = previewState().copy(
            gallery = previewState().gallery.copy(
                detailsVisible = true,
                previewExifLines = lines,
                detailsLineIndex = 0,
                previewAction = GalleryPreviewAction.Details,
            ),
        )

        val firstCannotUnderflow = reducer.reduce(open, GalleryCommand.NavigateUp)
        assertEquals(0, firstCannotUnderflow.state.gallery.detailsLineIndex)

        var current = open
        repeat(lines.size + 3) {
            current = reducer.reduce(current, GalleryCommand.NavigateDown).state
        }
        assertEquals(lines.lastIndex, current.gallery.detailsLineIndex)
        assertEquals(GalleryPreviewAction.Details, current.gallery.previewAction)

        val horizontal = reducer.reduce(current, GalleryCommand.NavigateRight)
        assertEquals(GalleryPreviewAction.Delete, horizontal.state.gallery.previewAction)
        assertEquals(lines.lastIndex, horizontal.state.gallery.detailsLineIndex)
    }

    @Test
    fun `every media-grid boundary direction focuses bottom back`() {
        val items = (0 until 8).map { index ->
            GalleryItem(index.toLong(), "media-$index.jpg", "DCIM/Camera/media-$index.jpg")
        }
        val base = AppState(
            mode = AppMode.Gallery,
            gallery = GalleryState(
                viewMode = GalleryViewMode.Browser,
                currentFolder = "bucket-camera",
                currentFolderName = "Camera",
                items = items,
            ),
        )
        val boundaryMoves = listOf(
            2 to GalleryCommand.NavigateUp,
            6 to GalleryCommand.NavigateDown,
            6 to GalleryCommand.NavigateLeft,
            5 to GalleryCommand.NavigateRight,
            7 to GalleryCommand.NavigateRight,
        )

        boundaryMoves.forEach { (index, command) ->
            val result = reducer.reduce(
                base.copy(gallery = base.gallery.copy(selectedIndex = index)),
                command,
            )
            assertTrue(result.state.gallery.browserBackFocused, "Expected $command from item $index to focus Back")
        }

        val interiorMove = reducer.reduce(
            base.copy(gallery = base.gallery.copy(selectedIndex = 1)),
            GalleryCommand.NavigateRight,
        )
        assertFalse(interiorMove.state.gallery.browserBackFocused)
        assertEquals(2, interiorMove.state.gallery.selectedIndex)
    }

    @Test
    fun `albums grid also exposes bottom back from every outer edge`() {
        val albums = (0 until 6).map { index ->
            GalleryItem(
                id = index.toLong(),
                name = "Album $index",
                path = "bucket-$index",
                isFolder = true,
            )
        }
        val base = AppState(
            mode = AppMode.Gallery,
            gallery = GalleryState(
                viewMode = GalleryViewMode.Browser,
                currentFolder = null,
                items = albums,
            ),
        )
        val boundaryMoves = listOf(
            1 to GalleryCommand.NavigateUp,
            4 to GalleryCommand.NavigateDown,
            4 to GalleryCommand.NavigateLeft,
            3 to GalleryCommand.NavigateRight,
            5 to GalleryCommand.NavigateRight,
        )

        boundaryMoves.forEach { (index, command) ->
            val result = reducer.reduce(
                base.copy(gallery = base.gallery.copy(selectedIndex = index)),
                command,
            )
            assertTrue(result.state.gallery.browserBackFocused, "Expected $command from album $index to focus Back")
        }

        val focusedBack = base.copy(
            gallery = base.gallery.copy(selectedIndex = 2, browserBackFocused = true),
        )
        assertEquals(
            0,
            reducer.reduce(focusedBack, GalleryCommand.NavigateUp).state.gallery.selectedIndex,
        )
        assertEquals(
            albums.lastIndex,
            reducer.reduce(focusedBack, GalleryCommand.NavigateDown).state.gallery.selectedIndex,
        )
    }

    @Test
    fun `media-grid back routes up to top down to bottom and has no delete action`() {
        val items = (0 until 14).map { index ->
            GalleryItem(index.toLong(), "media-$index.jpg", "DCIM/Camera/media-$index.jpg")
        }
        val state = AppState(
            mode = AppMode.Gallery,
            gallery = GalleryState(
                viewMode = GalleryViewMode.Browser,
                currentFolder = "bucket-camera",
                currentFolderName = "Camera",
                items = items,
                selectedIndex = 9,
                browserBackFocused = true,
            ),
        )

        val up = reducer.reduce(state, GalleryCommand.NavigateUp).state.gallery
        assertFalse(up.browserBackFocused)
        assertEquals(0, up.selectedIndex)

        val down = reducer.reduce(state, GalleryCommand.NavigateDown).state.gallery
        assertFalse(down.browserBackFocused)
        assertEquals(state.gallery.items.lastIndex, down.selectedIndex)

        val leftEdge = reducer.reduce(state, GalleryCommand.NavigateLeft).state.gallery
        assertNull(leftEdge.browserAction)
        assertFalse(leftEdge.browserBackFocused)
        assertEquals(6, leftEdge.selectedIndex)

        val rightEdge = reducer.reduce(state, GalleryCommand.NavigateRight).state.gallery
        assertNull(rightEdge.browserAction)
        assertFalse(rightEdge.browserBackFocused)
        assertEquals(11, rightEdge.selectedIndex)
        assertEquals(listOf(GalleryBrowserAction.Back), galleryBrowserActions(showingAlbums = false))
    }

    @Test
    fun `album-grid back returns to row edges with create album between back and right edge`() {
        val albums = (0 until 10).map { index ->
            GalleryItem(
                id = index.toLong(),
                name = "Album $index",
                path = "bucket-$index",
                isFolder = true,
            )
        }
        val state = AppState(
            mode = AppMode.Gallery,
            gallery = GalleryState(
                viewMode = GalleryViewMode.Browser,
                currentFolder = null,
                items = albums,
                selectedIndex = 6,
                browserBackFocused = true,
                browserAction = GalleryBrowserAction.Back,
            ),
        )

        val leftEdge = reducer.reduce(state, GalleryCommand.NavigateLeft).state.gallery
        assertNull(leftEdge.browserAction)
        assertFalse(leftEdge.browserBackFocused)
        assertEquals(4, leftEdge.selectedIndex)

        val createAlbum = reducer.reduce(state, GalleryCommand.NavigateRight).state.gallery
        assertEquals(GalleryBrowserAction.CreateAlbum, createAlbum.browserAction)
        assertFalse(createAlbum.browserBackFocused)

        val rightEdge = reducer.reduce(
            state.copy(gallery = createAlbum),
            GalleryCommand.NavigateRight,
        ).state.gallery
        assertNull(rightEdge.browserAction)
        assertFalse(rightEdge.browserBackFocused)
        assertEquals(7, rightEdge.selectedIndex)
    }

    @Test
    fun `album action menu previews deletes or backs while bottom rail only creates`() {
        val album = GalleryItem(
            id = 41,
            name = "Camera",
            path = "bucket-camera",
            relativePath = "DCIM/Camera/",
            isFolder = true,
            mediaCount = 12,
        )
        val albumBrowser = AppState(
            mode = AppMode.Gallery,
            gallery = GalleryState(items = listOf(album)),
        )
        assertEquals(
            listOf(GalleryBrowserAction.Back, GalleryBrowserAction.CreateAlbum),
            galleryBrowserActions(showingAlbums = true),
        )

        val menu = reducer.reduce(albumBrowser, GalleryCommand.OpenItem(0))
        assertEquals(GalleryViewMode.AlbumActions, menu.state.gallery.viewMode)
        assertEquals(GalleryAlbumAction.Preview, menu.state.gallery.albumAction)
        assertEquals(
            listOf(GalleryAlbumAction.Back, GalleryAlbumAction.Preview, GalleryAlbumAction.Delete),
            GalleryAlbumAction.entries,
        )

        val selectedDelete = reducer.reduce(menu.state, GalleryCommand.NavigateRight)
        assertEquals(GalleryAlbumAction.Delete, selectedDelete.state.gallery.albumAction)
        val albumDelete = reducer.reduce(
            selectedDelete.state,
            GalleryCommand.Confirm,
        )
        assertEquals(GalleryViewMode.ConfirmFolderDelete, albumDelete.state.gallery.viewMode)
        val confirmedDelete = reducer.reduce(
            albumDelete.state.copy(gallery = albumDelete.state.gallery.copy(confirmButtonIndex = 0)),
            GalleryCommand.Confirm,
        )
        assertEquals(listOf(PlatformEffect.DeleteGalleryAlbum(album)), confirmedDelete.effects)

        val backed = reducer.reduce(
            menu.state,
            GalleryCommand.ActivateAlbumAction(GalleryAlbumAction.Back),
        )
        assertEquals(GalleryViewMode.Browser, backed.state.gallery.viewMode)
        assertEquals(null, backed.state.gallery.currentFolder)
        assertTrue(backed.state.gallery.browserBackFocused)
        assertEquals(GalleryBrowserAction.Back, backed.state.gallery.browserAction)

        val create = reducer.reduce(
            albumBrowser,
            GalleryCommand.ActivateBrowserAction(GalleryBrowserAction.CreateAlbum),
        )
        assertEquals(GalleryViewMode.CreateFolder, create.state.gallery.viewMode)
        val cancelledCreate = reducer.reduce(create.state, GalleryCommand.Back)
        assertEquals(GalleryViewMode.Browser, cancelledCreate.state.gallery.viewMode)
        assertTrue(cancelledCreate.state.gallery.browserBackFocused)
        assertEquals(GalleryBrowserAction.Back, cancelledCreate.state.gallery.browserAction)
        val named = reducer.reduce(create.state, GalleryCommand.SetFolderName("Reef Survey"))
        val confirmedCreate = reducer.reduce(
            named.state.copy(gallery = named.state.gallery.copy(confirmButtonIndex = 0)),
            GalleryCommand.Confirm,
        )
        assertEquals(listOf(PlatformEffect.CreateGalleryFolder("Reef Survey")), confirmedCreate.effects)
        assertEquals(GalleryMutation.CreateAlbum, confirmedCreate.state.gallery.pendingMutation)

        val created = GalleryItem(
            id = 3,
            name = "Reef Survey",
            path = "empty:DCIM/Reef Survey/",
            relativePath = "DCIM/Reef Survey/",
            isFolder = true,
        )
        val loadedAfterCreate = reducer.reduce(
            confirmedCreate.state,
            GalleryCommand.LoadItems(listOf(created, album)),
        )
        assertEquals(0, loadedAfterCreate.state.gallery.selectedIndex)
        val completedCreate = reducer.reduce(
            loadedAfterCreate.state,
            GalleryCommand.OperationSucceeded("Created album Reef Survey"),
        )
        assertEquals(GalleryViewMode.Browser, completedCreate.state.gallery.viewMode)
        assertEquals(0, completedCreate.state.gallery.selectedIndex)
        assertEquals(created, completedCreate.state.gallery.items.first())
    }

    @Test
    fun `media action menu places preview between back and delete`() {
        val media = listOf(
            GalleryItem(1, "still.jpg", "DCIM/Camera/still.jpg"),
            GalleryItem(2, "clip.mp4", "DCIM/Camera/clip.mp4", isVideo = true),
        )
        val browser = AppState(
            mode = AppMode.Gallery,
            gallery = GalleryState(
                currentFolder = "bucket-camera",
                currentFolderName = "Camera",
                items = media,
                selectedIndex = 1,
            ),
        )

        val menu = reducer.reduce(browser, GalleryCommand.Confirm)
        assertEquals(GalleryViewMode.MediaActions, menu.state.gallery.viewMode)
        assertEquals(GalleryMediaAction.Preview, menu.state.gallery.mediaAction)
        assertEquals(
            listOf(GalleryMediaAction.Back, GalleryMediaAction.Preview, GalleryMediaAction.Delete),
            GalleryMediaAction.entries,
        )

        val back = reducer.reduce(menu.state, GalleryCommand.NavigateLeft)
        assertEquals(GalleryMediaAction.Back, back.state.gallery.mediaAction)
        val returnedBrowser = reducer.reduce(back.state, GalleryCommand.Confirm).state.gallery
        assertEquals(GalleryViewMode.Browser, returnedBrowser.viewMode)
        assertTrue(returnedBrowser.browserBackFocused)
        assertEquals(GalleryBrowserAction.Back, returnedBrowser.browserAction)

        val delete = reducer.reduce(menu.state, GalleryCommand.NavigateRight)
        assertEquals(GalleryMediaAction.Delete, delete.state.gallery.mediaAction)
        val confirmation = reducer.reduce(delete.state, GalleryCommand.Confirm)
        assertEquals(GalleryViewMode.ConfirmDelete, confirmation.state.gallery.viewMode)
        assertTrue(confirmation.state.gallery.confirmationReturnToMediaActions)
        val cancelled = reducer.reduce(confirmation.state, GalleryCommand.Back)
        assertEquals(GalleryViewMode.MediaActions, cancelled.state.gallery.viewMode)
        assertEquals(GalleryMediaAction.Back, cancelled.state.gallery.mediaAction)
    }

    @Test
    fun `selectable back controls traverse preview media albums and camera`() {
        val backFromPreview = reducer.reduce(
            previewState().copy(
                gallery = previewState().gallery.copy(
                    previewAction = GalleryPreviewAction.Back,
                    detailsVisible = true,
                ),
            ),
            GalleryCommand.Confirm,
        )
        assertEquals(GalleryViewMode.Browser, backFromPreview.state.gallery.viewMode)
        assertEquals("bucket-camera", backFromPreview.state.gallery.currentFolder)
        assertFalse(backFromPreview.state.gallery.detailsVisible)
        assertTrue(backFromPreview.state.gallery.browserBackFocused)
        assertEquals(GalleryBrowserAction.Back, backFromPreview.state.gallery.browserAction)

        val albums = reducer.reduce(backFromPreview.state, GalleryCommand.Confirm)
        assertEquals(null, albums.state.gallery.currentFolder)
        assertEquals(listOf(PlatformEffect.LoadGalleryItems), albums.effects)
        assertTrue(albums.state.gallery.browserBackFocused)
        assertEquals(GalleryBrowserAction.Back, albums.state.gallery.browserAction)

        val camera = reducer.reduce(albums.state, GalleryCommand.Confirm)
        assertEquals(AppMode.CameraLive, camera.state.mode)
    }

    @Test
    fun `back from an options page highlights preview back`() {
        val options = previewState().copy(
            gallery = previewState().gallery.copy(
                viewMode = GalleryViewMode.Options,
                previewAction = GalleryPreviewAction.Options,
            ),
        )

        val preview = reducer.reduce(options, GalleryCommand.Back).state.gallery

        assertEquals(GalleryViewMode.Preview, preview.viewMode)
        assertEquals(GalleryPreviewAction.Back, preview.previewAction)
    }

    @Test
    fun `down from browser back returns focus to the bottom-right grid item`() {
        val state = previewState().copy(
            gallery = previewState().gallery.copy(
                viewMode = GalleryViewMode.Browser,
                browserBackFocused = true,
            ),
        )

        val returned = reducer.reduce(state, GalleryCommand.NavigateDown)

        assertFalse(returned.state.gallery.browserBackFocused)
        assertEquals(state.gallery.items.lastIndex, returned.state.gallery.selectedIndex)
    }

    @Test
    fun `video starts stopped and explicit next also stops the next item`() {
        val state = previewState()
        assertFalse(state.gallery.videoPlaying)
        val playing = reducer.reduce(state, GalleryCommand.Confirm)
        assertTrue(playing.state.gallery.videoPlaying)

        val nextSelected = playing.state.copy(
            gallery = playing.state.gallery.copy(previewAction = GalleryPreviewAction.Next),
        )
        val next = reducer.reduce(nextSelected, GalleryCommand.Confirm)
        assertEquals(1, next.state.gallery.selectedIndex)
        assertFalse(next.state.gallery.videoPlaying, "The next item is a photo, so playback must stop.")
    }

    @Test
    fun `previous and next wrap across album ends`() {
        val first = previewState().copy(
            gallery = previewState().gallery.copy(previewAction = GalleryPreviewAction.Previous),
        )
        val wrappedToLast = reducer.reduce(first, GalleryCommand.Confirm)
        assertEquals(1, wrappedToLast.state.gallery.selectedIndex)
        assertFalse(wrappedToLast.state.gallery.videoPlaying)

        val last = wrappedToLast.state.copy(
            gallery = wrappedToLast.state.gallery.copy(previewAction = GalleryPreviewAction.Next),
        )
        val wrappedToFirst = reducer.reduce(last, GalleryCommand.Confirm)
        assertEquals(0, wrappedToFirst.state.gallery.selectedIndex)
        assertFalse(wrappedToFirst.state.gallery.videoPlaying, "A newly selected video must wait for explicit Play.")
    }

    @Test
    fun `options expose move and rename as confirmed MediaStore effects`() {
        val options = reducer.reduce(
            previewState(),
            GalleryCommand.ActivatePreviewAction(GalleryPreviewAction.Options),
        )
        assertEquals(GalleryViewMode.Options, options.state.gallery.viewMode)

        val move = reducer.reduce(options.state, GalleryCommand.Confirm)
        assertEquals(GalleryViewMode.Move, move.state.gallery.viewMode)
        assertEquals(listOf(PlatformEffect.LoadGalleryMoveTargets), move.effects)

        val rename = reducer.reduce(options.state, GalleryCommand.SelectOption(1))
        assertEquals(GalleryViewMode.Rename, rename.state.gallery.viewMode)
        val renamedDraft = reducer.reduce(rename.state, GalleryCommand.SetRenameDraft("dive-clip.mp4"))
        val save = reducer.reduce(renamedDraft.state, GalleryCommand.Confirm)
        assertEquals(GalleryMutation.Rename, save.state.gallery.pendingMutation)
        assertEquals(
            listOf(PlatformEffect.RenameGalleryItem(renamedDraft.state.gallery.items.first(), "dive-clip.mp4")),
            save.effects,
        )
    }

    @Test
    fun `delete remains cancel-first and runs only after explicit confirmation`() {
        val dialog = reducer.reduce(
            previewState(),
            GalleryCommand.ActivatePreviewAction(GalleryPreviewAction.Delete),
        )
        assertEquals(GalleryViewMode.ConfirmDelete, dialog.state.gallery.viewMode)
        assertEquals(1, dialog.state.gallery.confirmButtonIndex)
        assertTrue(dialog.effects.isEmpty())

        val selectedDelete = reducer.reduce(dialog.state, GalleryCommand.NavigateLeft)
        val confirmed = reducer.reduce(selectedDelete.state, GalleryCommand.Confirm)
        assertEquals(GalleryMutation.Delete, confirmed.state.gallery.pendingMutation)
        assertTrue(confirmed.effects.single() is PlatformEffect.DeleteGalleryItem)
    }

    @Test
    fun `deleting from preview opens next item in preview with delete selected`() {
        val dialog = reducer.reduce(
            previewState(),
            GalleryCommand.ActivatePreviewAction(GalleryPreviewAction.Delete),
        )
        val confirmed = reducer.reduce(
            dialog.state.copy(gallery = dialog.state.gallery.copy(confirmButtonIndex = 0)),
            GalleryCommand.Confirm,
        )
        val nextItem = confirmed.state.gallery.items[1]
        val loaded = reducer.reduce(
            confirmed.state,
            GalleryCommand.LoadItems(listOf(nextItem)),
        )
        val completed = reducer.reduce(
            loaded.state,
            GalleryCommand.OperationSucceeded("Deleted clip.mp4"),
        )

        assertEquals(GalleryViewMode.Preview, completed.state.gallery.viewMode)
        assertEquals(0, completed.state.gallery.selectedIndex)
        assertEquals(nextItem, completed.state.gallery.items.single())
        assertEquals(GalleryPreviewAction.Delete, completed.state.gallery.previewAction)
        assertFalse(completed.state.gallery.videoPlaying)
        assertFalse(completed.state.gallery.detailsVisible)
    }

    @Test
    fun `deleting last preview item wraps to first remaining item`() {
        val initial = previewState().copy(
            gallery = previewState().gallery.copy(selectedIndex = 1),
        )
        val dialog = reducer.reduce(
            initial,
            GalleryCommand.ActivatePreviewAction(GalleryPreviewAction.Delete),
        )
        val confirmed = reducer.reduce(
            dialog.state.copy(gallery = dialog.state.gallery.copy(confirmButtonIndex = 0)),
            GalleryCommand.Confirm,
        )
        val firstItem = initial.gallery.items.first()
        val loaded = reducer.reduce(
            confirmed.state,
            GalleryCommand.LoadItems(listOf(firstItem)),
        )
        val completed = reducer.reduce(
            loaded.state,
            GalleryCommand.OperationSucceeded("Deleted still.jpg"),
        )

        assertEquals(GalleryViewMode.Preview, completed.state.gallery.viewMode)
        assertEquals(0, completed.state.gallery.selectedIndex)
        assertEquals(firstItem, completed.state.gallery.items.single())
        assertEquals(GalleryPreviewAction.Delete, completed.state.gallery.previewAction)
    }

    @Test
    fun `gallery shutter and ok both execute the selected control`() {
        val router = InputRouter()
        val state = previewState().copy(housing = HousingState(inputEnabled = true))
        assertEquals(
            listOf(GalleryCommand.Confirm),
            router.route(state, HousingButtonEvent.Ok).commands,
        )
        assertEquals(
            listOf(GalleryCommand.Confirm),
            router.route(state, HousingButtonEvent.Shutter).commands,
        )
        assertEquals(
            listOf(GalleryCommand.Back),
            router.route(state, HousingButtonEvent.ZoomIn).commands,
        )
        assertEquals(
            listOf(GalleryCommand.Back),
            router.route(state, HousingButtonEvent.ZoomOut).commands,
        )
    }

    private fun previewState(): AppState = AppState(
        mode = AppMode.Gallery,
        gallery = GalleryState(
            viewMode = GalleryViewMode.Preview,
            currentFolder = "bucket-camera",
            currentFolderName = "Camera",
            items = listOf(
                GalleryItem(1, "clip.mp4", "DCIM/Camera/clip.mp4", isVideo = true),
                GalleryItem(2, "still.jpg", "DCIM/Camera/still.jpg"),
            ),
            previewAction = GalleryPreviewAction.PlayPause,
            videoPlaying = false,
        ),
    )
}
