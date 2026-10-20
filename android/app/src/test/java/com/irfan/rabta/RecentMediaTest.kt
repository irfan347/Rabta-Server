package com.irfan.rabta
import android.Manifest
import org.junit.Assert.*
import org.junit.Test
class RecentMediaTest {
    private val images = Manifest.permission.READ_MEDIA_IMAGES
    private val videos = Manifest.permission.READ_MEDIA_VIDEO
    private val selected = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    private val storage = Manifest.permission.READ_EXTERNAL_STORAGE
    private val gitHub = setOf(images, videos, selected, storage)
    /** The Google Play build's manifest takes the photo permissions out; older phones' storage permission stays. */
    private val play = setOf(storage)

    @Test fun theGitHubBuildAsksForThePhotosAsEachAndroidVersionNamesThem() {
        assertArrayEquals(arrayOf(images, videos, selected), RecentMedia.permissions(36, gitHub))
        assertArrayEquals(arrayOf(images, videos), RecentMedia.permissions(33, gitHub))
        assertArrayEquals(arrayOf(storage), RecentMedia.permissions(29, gitHub))
    }

    @Test fun thePlayBuildAsksForNothingFromAndroid13SoItOffersAndroidsPickerInstead() {
        assertEquals(0, RecentMedia.permissions(36, play).size)
        assertEquals(0, RecentMedia.permissions(34, play).size)
        assertEquals(0, RecentMedia.permissions(33, play).size)
        assertArrayEquals("Older phones still show their recent photos", arrayOf(storage), RecentMedia.permissions(32, play))
    }
}
