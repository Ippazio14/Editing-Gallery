package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import org.fossify.commons.dialogs.*
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.RadioItem
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivitySettingsBinding
import org.fossify.gallery.dialogs.*
import org.fossify.gallery.extensions.*
import org.fossify.gallery.helpers.*
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

class SettingsActivity : SimpleActivity() {

    private var mRecycleBinContentSize = 0L
    private val binding by viewBinding(ActivitySettingsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(
            padTopSystem = listOf(binding.settingsAppbar),
            padBottomSystem = listOf(binding.settingsNestedScrollview)
        )
        setupMaterialScrollListener(binding.settingsNestedScrollview, binding.settingsAppbar)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)
        setupSettingItems()
    }

    private fun setupSettingItems() {
        binding.settingsAbout.setOnClickListener { launchAbout() }
        setupCustomizeColors()
        setupUseEnglish()
        setupLanguage()
        setupFileLoadingPriority()
        setupManageIncludedFolders()
        setupManageHiddenFolders()
        setupRememberLastVideo()
        setupLoopVideos()
        setupOpenVideosOnSeparateScreen()
        setupOnVideoTap()
        setupDarkBackground()
        setupHideSystemUI()
        setupHiddenItemPasswordProtection()
        setupKeepScreenOn()
        setupAllowVideoGestures()
        setupShowNotch()
        setupKeepLastModified()
        setupUseRecycleBin()
        setupShowRecycleBin()
        setupShowRecycleBinLast()
        setupEmptyRecycleBin()
        updateTextColors(binding.settingsHolder)

        arrayOf(
            binding.settingsColorCustomizationSectionLabel,
            binding.settingsGeneralSettingsLabel,
            binding.settingsVideosLabel,
            binding.settingsFullscreenMediaLabel,
            binding.settingsSecurityLabel,
            binding.settingsFileOperationsLabel,
            binding.settingsRecycleBinLabel,
        ).forEach {
            it.setTextColor(getProperPrimaryColor())
        }
    }

    private fun setupCustomizeColors() {
        binding.settingsColorCustomizationHolder.setOnClickListener {
            startCustomizationActivity()
        }
    }

    private fun setupUseEnglish() {
        binding.settingsUseEnglishHolder.beVisibleIf((config.wasUseEnglishToggled || Locale.getDefault().language != "en") && !isTiramisuPlus())
        binding.settingsUseEnglish.isChecked = config.useEnglish
        binding.settingsUseEnglishHolder.setOnClickListener {
            binding.settingsUseEnglish.toggle()
            config.useEnglish = binding.settingsUseEnglish.isChecked
            exitProcess(0)
        }
    }

    private fun setupLanguage() {
        binding.settingsLanguage.text = Locale.getDefault().displayLanguage
        binding.settingsLanguageHolder.beVisibleIf(isTiramisuPlus())
        binding.settingsLanguageHolder.setOnClickListener {
            launchChangeAppLanguageIntent()
        }
    }

    private fun setupFileLoadingPriority() {
        binding.settingsFileLoadingPriorityHolder.beGoneIf(isRPlus() && !isExternalStorageManager())
        binding.settingsFileLoadingPriority.text = getFileLoadingPriorityText()
        binding.settingsFileLoadingPriorityHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(PRIORITY_SPEED, getString(R.string.speed)),
                RadioItem(PRIORITY_COMPROMISE, getString(R.string.compromise)),
                RadioItem(PRIORITY_VALIDITY, getString(R.string.avoid_showing_invalid_files))
            )

            RadioGroupDialog(this@SettingsActivity, items, config.fileLoadingPriority) {
                config.fileLoadingPriority = it as Int
                binding.settingsFileLoadingPriority.text = getFileLoadingPriorityText()
            }
        }
    }

    private fun getFileLoadingPriorityText() = getString(
        when (config.fileLoadingPriority) {
            PRIORITY_SPEED -> R.string.speed
            PRIORITY_COMPROMISE -> R.string.compromise
            else -> R.string.avoid_showing_invalid_files
        }
    )

    private fun setupManageIncludedFolders() {
        if (isRPlus() && !isExternalStorageManager()) {
            binding.settingsManageIncludedFolders.text =
                "${getString(R.string.manage_included_folders)} (${getString(org.fossify.commons.R.string.no_permission)})"
        } else {
            binding.settingsManageIncludedFolders.setText(R.string.manage_included_folders)
        }

        binding.settingsManageIncludedFoldersHolder.setOnClickListener {
            if (isRPlus() && !isExternalStorageManager()) {
                GrantAllFilesDialog(this)
            } else {
                startActivity(Intent(this, IncludedFoldersActivity::class.java))
            }
        }
    }

    private fun setupManageHiddenFolders() {
        binding.settingsManageHiddenFoldersHolder.beGoneIf(isQPlus())
        binding.settingsManageHiddenFoldersHolder.setOnClickListener {
            handleHiddenFolderPasswordProtection {
                startActivity(Intent(this, HiddenFoldersActivity::class.java))
            }
        }
    }

    private fun setupRememberLastVideo() {
        binding.settingsRememberLastVideoPosition.isChecked = config.rememberLastVideoPosition
        binding.settingsRememberLastVideoPositionHolder.setOnClickListener {
            binding.settingsRememberLastVideoPosition.toggle()
            config.rememberLastVideoPosition = binding.settingsRememberLastVideoPosition.isChecked
        }
    }

    private fun setupLoopVideos() {
        binding.settingsLoopVideos.isChecked = config.loopVideos
        binding.settingsLoopVideosHolder.setOnClickListener {
            binding.settingsLoopVideos.toggle()
            config.loopVideos = binding.settingsLoopVideos.isChecked
        }
    }

    private fun setupOpenVideosOnSeparateScreen() {
        binding.settingsOpenVideosOnSeparateScreen.isChecked = config.gestureVideoPlayer
        binding.settingsOpenVideosOnSeparateScreenHolder.setOnClickListener {
            binding.settingsOpenVideosOnSeparateScreen.toggle()
            config.gestureVideoPlayer = binding.settingsOpenVideosOnSeparateScreen.isChecked
        }
    }

    private fun setupOnVideoTap() {
        binding.settingsOnVideoTap.text = getVideoPlayerTypeText()
        binding.settingsOnVideoTapHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(VIDEO_PLAYER_APP, getString(R.string.open_in_app_player)),
                RadioItem(VIDEO_PLAYER_SYSTEM, getString(R.string.open_system_default_player))
            )

            RadioGroupDialog(
                activity = this@SettingsActivity,
                items = items,
                checkedItemId = config.videoPlayerType
            ) {
                config.videoPlayerType = it as Int
                binding.settingsOnVideoTap.text = getVideoPlayerTypeText()
            }
        }
    }

    private fun getVideoPlayerTypeText() = getString(
        when (config.videoPlayerType) {
            VIDEO_PLAYER_APP -> R.string.open_in_app_player
            else -> R.string.open_system_default_player
        }
    )

    private fun setupDarkBackground() {
        binding.settingsBlackBackground.isChecked = config.blackBackground
        binding.settingsBlackBackgroundHolder.setOnClickListener {
            binding.settingsBlackBackground.toggle()
            config.blackBackground = binding.settingsBlackBackground.isChecked
        }
    }

    private fun setupHideSystemUI() {
        binding.settingsHideSystemUi.isChecked = config.hideSystemUI
        binding.settingsHideSystemUiHolder.setOnClickListener {
            binding.settingsHideSystemUi.toggle()
            config.hideSystemUI = binding.settingsHideSystemUi.isChecked
        }
    }

    private fun setupHiddenItemPasswordProtection() {
        binding.settingsHiddenItemPasswordProtectionHolder.beGoneIf(isRPlus() && !isExternalStorageManager())
        binding.settingsHiddenItemPasswordProtection.isChecked = config.isHiddenPasswordProtectionOn
        binding.settingsHiddenItemPasswordProtectionHolder.setOnClickListener {
            val tabToShow = if (config.isHiddenPasswordProtectionOn) config.hiddenProtectionType else SHOW_ALL_TABS
            SecurityDialog(this, config.hiddenPasswordHash, tabToShow) { hash, type, success ->
                if (success) {
                    val hasPasswordProtection = config.isHiddenPasswordProtectionOn
                    binding.settingsHiddenItemPasswordProtection.isChecked = !hasPasswordProtection
                    config.isHiddenPasswordProtectionOn = !hasPasswordProtection
                    config.hiddenPasswordHash = if (hasPasswordProtection) "" else hash
                    config.hiddenProtectionType = type

                    if (config.isHiddenPasswordProtectionOn) {
                        val confirmationTextId = if (config.hiddenProtectionType == PROTECTION_FINGERPRINT)
                            org.fossify.commons.R.string.fingerprint_setup_successfully else org.fossify.commons.R.string.protection_setup_successfully
                        ConfirmationDialog(this, "", confirmationTextId, org.fossify.commons.R.string.ok, 0) { }
                    }
                }
            }
        }
    }

    private fun setupKeepScreenOn() {
        binding.settingsKeepScreenOnFullscreenPhotos.isChecked = config.keepScreenOn
        binding.settingsKeepScreenOnFullscreenPhotosHolder.setOnClickListener {
            binding.settingsKeepScreenOnFullscreenPhotos.toggle()
            config.keepScreenOn = binding.settingsKeepScreenOnFullscreenPhotos.isChecked
        }
    }

    private fun setupAllowVideoGestures() {
        binding.settingsAllowVideoGestures.isChecked = config.allowVideoGestures
        binding.settingsAllowVideoGesturesHolder.setOnClickListener {
            binding.settingsAllowVideoGestures.toggle()
            config.allowVideoGestures = binding.settingsAllowVideoGestures.isChecked
        }
    }

    private fun setupShowNotch() {
        binding.settingsShowNotchHolder.beVisibleIf(isPiePlus())
        binding.settingsShowNotch.isChecked = config.showNotch
        binding.settingsShowNotchHolder.setOnClickListener {
            binding.settingsShowNotch.toggle()
            config.showNotch = binding.settingsShowNotch.isChecked
        }
    }

    private fun getFolderStyleText() = getString(
        when (config.folderStyle) {
            FOLDER_STYLE_SQUARE -> R.string.square
            else -> R.string.rounded_corners
        }
    )

    private fun setupKeepLastModified() {
        binding.settingsKeepLastModified.isChecked = config.keepLastModified
        binding.settingsKeepLastModifiedHolder.setOnClickListener {
            handleMediaManagementPrompt {
                binding.settingsKeepLastModified.toggle()
                config.keepLastModified = binding.settingsKeepLastModified.isChecked
            }
        }
    }

    private fun setupUseRecycleBin() {
        updateRecycleBinButtons()
        binding.settingsUseRecycleBin.isChecked = config.useRecycleBin
        binding.settingsUseRecycleBinHolder.setOnClickListener {
            binding.settingsUseRecycleBin.toggle()
            config.useRecycleBin = binding.settingsUseRecycleBin.isChecked
            updateRecycleBinButtons()
        }
    }

    private fun setupShowRecycleBin() {
        binding.settingsShowRecycleBin.isChecked = config.showRecycleBinAtFolders
        binding.settingsShowRecycleBinHolder.setOnClickListener {
            binding.settingsShowRecycleBin.toggle()
            config.showRecycleBinAtFolders = binding.settingsShowRecycleBin.isChecked
            updateRecycleBinButtons()
        }
    }

    private fun setupShowRecycleBinLast() {
        binding.settingsShowRecycleBinLast.isChecked = config.showRecycleBinLast
        binding.settingsShowRecycleBinLastHolder.setOnClickListener {
            binding.settingsShowRecycleBinLast.toggle()
            config.showRecycleBinLast = binding.settingsShowRecycleBinLast.isChecked
            if (config.showRecycleBinLast) {
                config.removePinnedFolders(setOf(RECYCLE_BIN))
            }
        }
    }

    private fun updateRecycleBinButtons() {
        binding.settingsShowRecycleBinLastHolder.beVisibleIf(config.useRecycleBin && config.showRecycleBinAtFolders)
        binding.settingsEmptyRecycleBinHolder.beVisibleIf(config.useRecycleBin)
        binding.settingsShowRecycleBinHolder.beVisibleIf(config.useRecycleBin)
    }

    private fun setupEmptyRecycleBin() {
        ensureBackgroundThread {
            try {
                mRecycleBinContentSize = mediaDB.getDeletedMedia().sumByLong { medium ->
                    val size = medium.size
                    if (size == 0L) {
                        val path = medium.path.removePrefix(RECYCLE_BIN).prependIndent(recycleBinPath)
                        File(path).length()
                    } else {
                        size
                    }
                }
            } catch (ignored: Exception) {
            }

            runOnUiThread {
                binding.settingsEmptyRecycleBinSize.text = mRecycleBinContentSize.formatSize()
            }
        }

        binding.settingsEmptyRecycleBinHolder.setOnClickListener {
            if (mRecycleBinContentSize == 0L) {
                toast(org.fossify.commons.R.string.recycle_bin_empty)
            } else {
                showRecycleBinEmptyingDialog {
                    emptyTheRecycleBin()
                    mRecycleBinContentSize = 0L
                    binding.settingsEmptyRecycleBinSize.text = 0L.formatSize()
                }
            }
        }
    }

}
