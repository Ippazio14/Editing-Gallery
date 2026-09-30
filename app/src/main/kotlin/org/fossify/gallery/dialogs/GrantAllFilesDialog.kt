package org.fossify.gallery.dialogs

import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.toast

/** Hidden and non-media files are unavailable without broad storage access. */
class GrantAllFilesDialog(activity: BaseSimpleActivity) {
    init {
        activity.toast(org.fossify.commons.R.string.no_permission)
    }
}
