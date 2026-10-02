package org.fossify.gallery.fragments

import android.provider.MediaStore
import androidx.fragment.app.Fragment
import org.fossify.commons.extensions.*
import org.fossify.gallery.helpers.*
import org.fossify.gallery.models.Medium

abstract class ViewPagerFragment : Fragment() {
    var listener: FragmentListener? = null

    abstract fun fullscreenToggled(isFullscreen: Boolean)

    interface FragmentListener {
        fun fragmentClicked()

        fun videoEnded(): Boolean

        fun goToPrevItem()

        fun goToNextItem()

        fun launchViewVideoIntent(path: String)

        fun isSlideShowActive(): Boolean

        fun isFullScreen(): Boolean
    }

    fun getPathToLoad(medium: Medium): String {
        val context = context ?: return medium.path
        return if (context.isPathOnOTG(medium.path)) {
            medium.path.getOTGPublicPath(context)
        } else {
            medium.path
        }
    }

}
