package com.github.andreyasadchy.xtra.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentChatBinding
import com.github.andreyasadchy.xtra.model.Account
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.main.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.BasePlayerFragment
import com.github.andreyasadchy.xtra.ui.player.stream.StreamPlayerFragment
import com.github.andreyasadchy.xtra.ui.view.chat.MessageClickedDialog
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.LifecycleListener
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.chat.Raid
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.visible
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ChatFragment : BaseNetworkFragment(), LifecycleListener, MessageClickedDialog.OnButtonClickListener {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.integrity.observe(viewLifecycleOwner) {
            if (requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false) && requireContext().prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true)) {
                IntegrityDialog.show(childFragmentManager)
            }
        }
        with(binding) {
            val args = requireArguments()
            val channelId = args.getString(KEY_CHANNEL_ID)
            val isLive = args.getBoolean(KEY_IS_LIVE)
            val account = Account.get(requireContext())
            val isLoggedIn = !account.login.isNullOrBlank() && (!TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() || !account.helixToken.isNullOrBlank())
            val chatUrl = args.getString(KEY_CHAT_URL)
            val enableChat = when {
                requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) -> false
                isLive -> {
                    chatView.init(this@ChatFragment, channelId)
                    chatView.setCallback(viewModel)
                    if (isLoggedIn) {
                        chatView.setUsername(account.login)
                        chatView.addToAutoCompleteList(viewModel.chatters)
                        viewModel.recentEmotes.observe(viewLifecycleOwner, Observer(chatView::setRecentEmotes))
                        viewModel.userEmotes.observe(viewLifecycleOwner, Observer(chatView::addToAutoCompleteList))
                        viewModel.newChatter.observe(viewLifecycleOwner) { chatView.addToAutoCompleteList(listOf(it)) }
                    }
                    true
                }
                chatUrl != null || (args.getString(KEY_VIDEO_ID) != null && !args.getBoolean(KEY_START_TIME_EMPTY)) -> {
                    chatView.init(this@ChatFragment, channelId, viewModel::getEmoteBytes, chatUrl)
                    true
                }
                else -> {
                    requireView().findViewById<TextView>(R.id.chatReplayUnavailable)?.visible()
                    false
                }
            }
            if (enableChat) {
                chatView.enableChatInteraction(isLive && isLoggedIn)
                viewModel.chatMessages.observe(viewLifecycleOwner, Observer(chatView::submitList))
                viewModel.newMessage.observe(viewLifecycleOwner) { chatView.notifyMessageAdded() }
                viewModel.localTwitchEmotes.observe(viewLifecycleOwner, Observer(chatView::addLocalTwitchEmotes))
                viewModel.globalStvEmotes.observe(viewLifecycleOwner, Observer(chatView::addGlobalStvEmotes))
                viewModel.channelStvEmotes.observe(viewLifecycleOwner, Observer(chatView::addChannelStvEmotes))
                viewModel.globalBttvEmotes.observe(viewLifecycleOwner, Observer(chatView::addGlobalBttvEmotes))
                viewModel.channelBttvEmotes.observe(viewLifecycleOwner, Observer(chatView::addChannelBttvEmotes))
                viewModel.globalFfzEmotes.observe(viewLifecycleOwner, Observer(chatView::addGlobalFfzEmotes))
                viewModel.channelFfzEmotes.observe(viewLifecycleOwner, Observer(chatView::addChannelFfzEmotes))
                viewModel.globalBadges.observe(viewLifecycleOwner, Observer(chatView::addGlobalBadges))
                viewModel.channelBadges.observe(viewLifecycleOwner, Observer(chatView::addChannelBadges))
                viewModel.cheerEmotes.observe(viewLifecycleOwner, Observer(chatView::addCheerEmotes))
                viewModel.roomState.observe(viewLifecycleOwner) { chatView.notifyRoomState(it) }
                viewModel.reloadMessages.observe(viewLifecycleOwner) { chatView.notifyEmotesLoaded() }
                viewModel.scrollDown.observe(viewLifecycleOwner) { chatView.scrollToLastPosition() }
                viewModel.hideRaid.observe(viewLifecycleOwner) { chatView.hideRaid() }
                viewModel.raid.observe(viewLifecycleOwner) { onRaidUpdate(it) }
                viewModel.raidClicked.observe(viewLifecycleOwner) { onRaidClicked() }
                viewModel.streamLiveChanged.observe(viewLifecycleOwner) { (parentFragment as? StreamPlayerFragment)?.updateLive(it.first.live, it.first.serverTime?.times(1000), it.second) }
                viewModel.viewerCount.observe(viewLifecycleOwner) { (parentFragment as? StreamPlayerFragment)?.updateViewerCount(it) }
                viewModel.title.observe(viewLifecycleOwner) { (parentFragment as? StreamPlayerFragment)?.updateTitle(it?.title, it?.gameId, null, it?.gameName) }
            }
            if (chatUrl != null) {
                initialize()
            }
        }
    }

    override fun initialize() {
        val args = requireArguments()
        val channelId = args.getString(KEY_CHANNEL_ID)
        val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
        val channelName = args.getString(KEY_CHANNEL_NAME)
        val account = Account.get(requireContext())
        val isLoggedIn = !account.login.isNullOrBlank() && (!TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() || !account.helixToken.isNullOrBlank())
        val messageLimit = requireContext().prefs().getInt(C.CHAT_LIMIT, 600)
        val useChatWebSocket = requireContext().prefs().getBoolean(C.CHAT_USE_WEBSOCKET, false)
        val useSSL = requireContext().prefs().getBoolean(C.CHAT_USE_SSL, true)
        val usePubSub = requireContext().prefs().getBoolean(C.CHAT_PUBSUB_ENABLED, true)
        val helixClientId = requireContext().prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi")
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), true)
        val emoteQuality =  requireContext().prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4"
        val animateGifs =  requireContext().prefs().getBoolean(C.ANIMATED_EMOTES, true)
        val showUserNotice = requireContext().prefs().getBoolean(C.CHAT_SHOW_USERNOTICE, true)
        val showClearMsg = requireContext().prefs().getBoolean(C.CHAT_SHOW_CLEARMSG, true)
        val showClearChat = requireContext().prefs().getBoolean(C.CHAT_SHOW_CLEARCHAT, true)
        val collectPoints = requireContext().prefs().getBoolean(C.CHAT_POINTS_COLLECT, true)
        val notifyPoints = requireContext().prefs().getBoolean(C.CHAT_POINTS_NOTIFY, false)
        val showRaids = requireContext().prefs().getBoolean(C.CHAT_RAIDS_SHOW, true)
        val autoSwitchRaids = requireContext().prefs().getBoolean(C.CHAT_RAIDS_AUTO_SWITCH, true)
        val enableRecentMsg = requireContext().prefs().getBoolean(C.CHAT_RECENT, true)
        val recentMsgLimit = requireContext().prefs().getInt(C.CHAT_RECENT_LIMIT, 100)
        val enableStv = requireContext().prefs().getBoolean(C.CHAT_ENABLE_STV, true)
        val enableBttv = requireContext().prefs().getBoolean(C.CHAT_ENABLE_BTTV, true)
        val enableFfz = requireContext().prefs().getBoolean(C.CHAT_ENABLE_FFZ, true)
        val checkIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false) && requireContext().prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true)
        val useApiCommands = requireContext().prefs().getBoolean(C.DEBUG_API_COMMANDS, true)
        val useApiChatMessages = requireContext().prefs().getBoolean(C.DEBUG_API_CHAT_MESSAGES, false)
        val useEventSubChat = requireContext().prefs().getBoolean(C.DEBUG_EVENTSUB_CHAT, false)
        val disableChat = requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)
        val isLive = args.getBoolean(KEY_IS_LIVE)
        if (!disableChat) {
            if (isLive) {
                val streamId = args.getString(KEY_STREAM_ID)
                viewModel.startLive(useChatWebSocket, useSSL, usePubSub, account, isLoggedIn, helixClientId, gqlHeaders, channelId, channelLogin, channelName, streamId, messageLimit, emoteQuality, animateGifs, showUserNotice, showClearMsg, showClearChat, collectPoints, notifyPoints, showRaids, autoSwitchRaids, enableRecentMsg, recentMsgLimit.toString(), enableStv, enableBttv, enableFfz, checkIntegrity, useApiCommands, useApiChatMessages, useEventSubChat)
            } else {
                val chatUrl = args.getString(KEY_CHAT_URL)
                val videoId = args.getString(KEY_VIDEO_ID)
                if (chatUrl != null || (videoId != null && !args.getBoolean(KEY_START_TIME_EMPTY))) {
                    val startTime = args.getInt(KEY_START_TIME)
                    val getCurrentPosition = (parentFragment as BasePlayerFragment)::getCurrentPosition
                    val getCurrentSpeed = (parentFragment as BasePlayerFragment)::getCurrentSpeed
                    viewModel.startReplay(helixClientId, account.helixToken, gqlHeaders, channelId, channelLogin, chatUrl, videoId, startTime, getCurrentPosition, getCurrentSpeed, messageLimit, emoteQuality, animateGifs, enableStv, enableBttv, enableFfz, checkIntegrity)
                }
            }
        }
    }

    fun isActive(): Boolean? {
        return (viewModel.chat as? ChatViewModel.LiveChatController)?.isActive()
    }

    fun disconnect() {
        (viewModel.chat as? ChatViewModel.LiveChatController)?.disconnect()
    }

    fun reconnect() {
        (viewModel.chat as? ChatViewModel.LiveChatController)?.start()
        val channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN)
        val enableRecentMsg = requireContext().prefs().getBoolean(C.CHAT_RECENT, true)
        val recentMsgLimit = requireContext().prefs().getInt(C.CHAT_RECENT_LIMIT, 100)
        if (channelLogin != null && enableRecentMsg) {
            viewModel.loadRecentMessages(channelLogin, recentMsgLimit.toString())
        }
    }

    fun reloadEmotes() {
        val channelId = requireArguments().getString(KEY_CHANNEL_ID)
        val channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN)
        val helixClientId = requireContext().prefs().getString(C.HELIX_CLIENT_ID, "ilfexgv3nnljz3isbm257gzwrzr7bi")
        val helixToken = Account.get(requireContext()).helixToken
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext())
        val emoteQuality =  requireContext().prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4"
        val animateGifs =  requireContext().prefs().getBoolean(C.ANIMATED_EMOTES, true)
        val enableStv = requireContext().prefs().getBoolean(C.CHAT_ENABLE_STV, true)
        val enableBttv = requireContext().prefs().getBoolean(C.CHAT_ENABLE_BTTV, true)
        val enableFfz = requireContext().prefs().getBoolean(C.CHAT_ENABLE_FFZ, true)
        val checkIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false) && requireContext().prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true)
        viewModel.reloadEmotes(helixClientId, helixToken, gqlHeaders, channelId, channelLogin, emoteQuality, animateGifs, enableStv, enableBttv, enableFfz, checkIntegrity)
    }

    fun updatePosition(position: Long) {
        (viewModel.chat as? ChatViewModel.VideoChatController)?.updatePosition(position)
    }

    fun updateSpeed(speed: Float) {
        (viewModel.chat as? ChatViewModel.VideoChatController)?.updateSpeed(speed)
    }

    fun updateStreamId(id: String?) {
        viewModel.streamId = id
    }

    private fun onRaidUpdate(raid: Raid) {
        if (viewModel.raidClosed && viewModel.raidNewId) {
            viewModel.raidAutoSwitch = requireContext().prefs().getBoolean(C.CHAT_RAIDS_AUTO_SWITCH, true)
            viewModel.raidClosed = false
        }
        if (raid.openStream) {
            if (!viewModel.raidClosed) {
                if (viewModel.raidAutoSwitch) {
                    if (parentFragment is BasePlayerFragment && (parentFragment as? BasePlayerFragment)?.isSleepTimerActive() != true) {
                        onRaidClicked()
                    }
                } else {
                    viewModel.raidAutoSwitch = requireContext().prefs().getBoolean(C.CHAT_RAIDS_AUTO_SWITCH, true)
                }
                binding.chatView.hideRaid()
            } else {
                viewModel.raidAutoSwitch = requireContext().prefs().getBoolean(C.CHAT_RAIDS_AUTO_SWITCH, true)
                viewModel.raidClosed = false
            }
        } else {
            if (!viewModel.raidClosed) {
                binding.chatView.notifyRaid(raid, viewModel.raidNewId)
            }
        }
    }

    private fun onRaidClicked() {
        viewModel.raid.value?.let {
            (requireActivity() as MainActivity).startStream(Stream(
                channelId = it.targetId,
                channelLogin = it.targetLogin,
                channelName = it.targetName,
                profileImageUrl = it.targetProfileImage,
            ))
        }
    }

    fun emoteMenuIsVisible() = binding.chatView.emoteMenuIsVisible()

    fun toggleEmoteMenu(enable: Boolean) = binding.chatView.toggleEmoteMenu(enable)

    fun toggleBackPressedCallback(enable: Boolean) = binding.chatView.toggleBackPressedCallback(enable)

    fun appendEmote(emote: Emote) {
        binding.chatView.appendEmote(emote)
    }

    override fun onReplyClicked(userName: String) {
        binding.chatView.reply(userName)
    }

    override fun onCopyMessageClicked(message: String) {
        binding.chatView.setMessage(message)
    }

    override fun onViewProfileClicked(id: String?, login: String?, name: String?, channelLogo: String?) {
        findNavController().navigate(ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
            channelId = id,
            channelLogin = login,
            channelName = name,
            channelLogo = channelLogo
        ))
        (parentFragment as? BasePlayerFragment)?.minimize()
    }

    override fun onNetworkRestored() {
        if (isResumed) {
            viewModel.start()
        }
    }

    override fun onMovedToBackground() {
        if (!requireArguments().getBoolean(KEY_IS_LIVE) || !requireContext().prefs().getBoolean(C.PLAYER_KEEP_CHAT_OPEN, false) || requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)) {
            viewModel.stop()
        }
    }

    override fun onMovedToForeground() {
        if (!requireArguments().getBoolean(KEY_IS_LIVE) || !requireContext().prefs().getBoolean(C.PLAYER_KEEP_CHAT_OPEN, false) || requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)) {
            viewModel.start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val KEY_IS_LIVE = "isLive"
        private const val KEY_CHANNEL_ID = "channel_id"
        private const val KEY_CHANNEL_LOGIN = "channel_login"
        private const val KEY_CHANNEL_NAME = "channel_name"
        private const val KEY_STREAM_ID = "streamId"
        private const val KEY_VIDEO_ID = "videoId"
        private const val KEY_CHAT_URL = "chatUrl"
        private const val KEY_START_TIME_EMPTY = "startTime_empty"
        private const val KEY_START_TIME = "startTime"

        fun newInstance(channelId: String?, channelLogin: String?, channelName: String?, streamId: String?) = ChatFragment().apply {
            arguments = Bundle().apply {
                putBoolean(KEY_IS_LIVE, true)
                putString(KEY_CHANNEL_ID, channelId)
                putString(KEY_CHANNEL_LOGIN, channelLogin)
                putString(KEY_CHANNEL_NAME, channelName)
                putString(KEY_STREAM_ID, streamId)
            }
        }

        fun newInstance(channelId: String?, channelLogin: String?, videoId: String?, startTime: Int?) = ChatFragment().apply {
            arguments = Bundle().apply {
                putBoolean(KEY_IS_LIVE, false)
                putString(KEY_CHANNEL_ID, channelId)
                putString(KEY_CHANNEL_LOGIN, channelLogin)
                putString(KEY_VIDEO_ID, videoId)
                if (startTime != null) {
                    putBoolean(KEY_START_TIME_EMPTY, false)
                    putInt(KEY_START_TIME, startTime)
                } else {
                    putBoolean(KEY_START_TIME_EMPTY, true)
                }
            }
        }

        fun newLocalInstance(channelId: String?, channelLogin: String?, chatUrl: String?) = ChatFragment().apply {
            arguments = Bundle().apply {
                putString(KEY_CHANNEL_ID, channelId)
                putString(KEY_CHANNEL_LOGIN, channelLogin)
                putString(KEY_CHAT_URL, chatUrl)
            }
        }
    }
}