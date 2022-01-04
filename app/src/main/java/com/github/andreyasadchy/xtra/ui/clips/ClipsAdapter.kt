package com.github.andreyasadchy.xtra.ui.clips

import android.text.format.DateUtils
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.helix.clip.Clip
import com.github.andreyasadchy.xtra.ui.common.BasePagedListAdapter
import com.github.andreyasadchy.xtra.ui.common.OnChannelSelectedListener
import com.github.andreyasadchy.xtra.ui.common.OnGameSelectedListener
import com.github.andreyasadchy.xtra.util.*
import kotlinx.android.synthetic.main.fragment_videos_list_item.view.*

class ClipsAdapter(
        private val fragment: Fragment,
        private val clickListener: BaseClipsFragment.OnClipSelectedListener,
        private val channelClickListener: OnChannelSelectedListener,
        private val gameClickListener: OnGameSelectedListener,
        private val showDownloadDialog: (Clip) -> Unit) : BasePagedListAdapter<Clip>(
        object : DiffUtil.ItemCallback<Clip>() {
            override fun areItemsTheSame(oldItem: Clip, newItem: Clip): Boolean =
                    oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Clip, newItem: Clip): Boolean =
                    oldItem.view_count == newItem.view_count &&
                            oldItem.title == newItem.title

        }) {

    override val layoutId: Int = R.layout.fragment_videos_list_item

    override fun bind(item: Clip, view: View) {
        val channelListener: (View) -> Unit = { channelClickListener.viewChannel(item.broadcaster_id, item.broadcaster_login, item.broadcaster_name, item.channelLogo) }
        val gameListener: (View) -> Unit = { gameClickListener.openGame(item.gameId, item.gameName) }
        with(view) {
            setOnClickListener { clickListener.startClip(item) }
            setOnLongClickListener { showDownloadDialog(item); true }
            thumbnail.loadImage(fragment, item.thumbnail, diskCacheStrategy = DiskCacheStrategy.NONE)
            date.text = item.uploadDate?.let { TwitchApiHelper.formatTimeString(context, it) }
            views.text = item.view_count?.let { TwitchApiHelper.formatViewsCount(context, it, context.prefs().getBoolean(C.UI_TRUNCATEVIEWCOUNT, false)) }
            duration.text = item.duration?.let { DateUtils.formatElapsedTime(it.toLong()) }
            if (item.channelLogo != null)  {
                userImage.visible()
                userImage.loadImage(fragment, item.channelLogo, circle = true)
                userImage.setOnClickListener(channelListener)
            }
            if (item.broadcaster_name != null)  {
                username.visible()
                username.text = item.broadcaster_name
                username.setOnClickListener(channelListener)
            }
            if (item.title != null)  {
                title.visible()
                title.text = item.title.trim()
            }
            if (item.gameName != null)  {
                gameName.visible()
                gameName.text = item.gameName
                gameName.setOnClickListener(gameListener)
            }
            options.setOnClickListener {
                PopupMenu(context, options).apply {
                    inflate(R.menu.media_item)
                    setOnMenuItemClickListener {
                        showDownloadDialog(item)
                        return@setOnMenuItemClickListener true
                    }
                    show()
                }
            }
        }
    }
}