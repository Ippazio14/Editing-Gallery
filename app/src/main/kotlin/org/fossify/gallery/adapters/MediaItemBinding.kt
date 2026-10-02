package org.fossify.gallery.adapters

import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.fossify.commons.views.MySquareImageView
import org.fossify.gallery.databinding.PhotoItemGridBinding
import org.fossify.gallery.databinding.VideoItemGridBinding

interface MediaItemBinding {
    val root: ViewGroup
    val mediaItemHolder: ViewGroup
    val playPortraitOutline: ImageView?
    val fileType: TextView?
    val mediumName: TextView
    val videoDuration: TextView?
    val mediumCheck: ImageView
    val mediumThumbnail: MySquareImageView
}

class PhotoGridMediaItemBinding(val binding: PhotoItemGridBinding) : MediaItemBinding {
    override val root: ViewGroup = binding.root
    override val mediaItemHolder: ViewGroup = binding.mediaItemHolder
    override val playPortraitOutline: ImageView? = null
    override val fileType: TextView = binding.fileType
    override val mediumName: TextView = binding.mediumName
    override val videoDuration: TextView? = null
    override val mediumCheck: ImageView = binding.mediumCheck
    override val mediumThumbnail: MySquareImageView = binding.mediumThumbnail
}

fun PhotoItemGridBinding.toMediaItemBinding() = PhotoGridMediaItemBinding(this)

class VideoGridMediaItemBinding(val binding: VideoItemGridBinding) : MediaItemBinding {
    override val root: ViewGroup = binding.root
    override val mediaItemHolder: ViewGroup = binding.mediaItemHolder
    override val playPortraitOutline: ImageView = binding.playPortraitOutline
    override val fileType: TextView? = null
    override val mediumName: TextView = binding.mediumName
    override val videoDuration: TextView = binding.videoDuration
    override val mediumCheck: ImageView = binding.mediumCheck
    override val mediumThumbnail: MySquareImageView = binding.mediumThumbnail
}

fun VideoItemGridBinding.toMediaItemBinding() = VideoGridMediaItemBinding(this)
