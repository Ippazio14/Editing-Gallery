package org.fossify.gallery.activities

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.provider.MediaStore.Images
import android.provider.MediaStore.Video
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.dialogs.CreateNewFolderDialog
import org.fossify.commons.dialogs.FilePickerDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.checkWhatsNew
import org.fossify.commons.extensions.deleteFiles
import org.fossify.commons.extensions.getDoesFilePathExist
import org.fossify.commons.extensions.getFilePublicUri
import org.fossify.commons.extensions.getLatestMediaByDateId
import org.fossify.commons.extensions.getLatestMediaId
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getStorageDirectories
import org.fossify.commons.extensions.getTimeFormat
import org.fossify.commons.extensions.handleLockedFolderOpening
import org.fossify.commons.extensions.hasAllPermissions
import org.fossify.commons.extensions.hasOTGConnected
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isExternalStorageManager
import org.fossify.commons.extensions.isGone
import org.fossify.commons.extensions.isMediaFile
import org.fossify.commons.extensions.isPathOnOTG
import org.fossify.commons.extensions.recycleBinPath
import org.fossify.commons.extensions.sdCardPath
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toFileDirItem
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.PERMISSION_READ_STORAGE
import org.fossify.commons.helpers.SORT_BY_DATE_MODIFIED
import org.fossify.commons.helpers.SORT_BY_DATE_TAKEN
import org.fossify.commons.helpers.SORT_BY_SIZE
import org.fossify.commons.helpers.SORT_USE_NUMERIC_VALUE
import org.fossify.commons.helpers.VIEW_TYPE_GRID
import org.fossify.commons.helpers.VIEW_TYPE_LIST
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.models.RadioItem
import org.fossify.commons.models.Release
import org.fossify.commons.views.MyGridLayoutManager
import org.fossify.commons.views.MyRecyclerView
import org.fossify.gallery.BuildConfig
import org.fossify.gallery.R
import org.fossify.gallery.adapters.DirectoryAdapter
import org.fossify.gallery.databases.GalleryDatabase
import org.fossify.gallery.databinding.ActivityMainBinding
import org.fossify.gallery.dialogs.ChangeSortingDialog
import org.fossify.gallery.extensions.addTempFolderIfNeeded
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.createDirectoryFromMedia
import org.fossify.gallery.extensions.directoryDB
import org.fossify.gallery.extensions.getCachedDirectories
import org.fossify.gallery.extensions.getCachedMedia
import org.fossify.gallery.extensions.getDirectorySortingValue
import org.fossify.gallery.extensions.getDirsToShow
import org.fossify.gallery.extensions.getDistinctPath
import org.fossify.gallery.extensions.getNoMediaFoldersSync
import org.fossify.gallery.extensions.getOTGFolderChildrenNames
import org.fossify.gallery.extensions.getSortedDirectories
import org.fossify.gallery.extensions.handleMediaManagementPrompt
import org.fossify.gallery.extensions.launchSettings
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.movePathsInRecycleBin
import org.fossify.gallery.extensions.movePinnedDirectoriesToFront
import org.fossify.gallery.extensions.removeInvalidDBDirectories
import org.fossify.gallery.extensions.storeDirectoryItems
import org.fossify.gallery.extensions.updateDBDirectory
import org.fossify.gallery.extensions.updateWidgets
import org.fossify.gallery.helpers.DIRECTORY
import org.fossify.gallery.helpers.GET_ANY_INTENT
import org.fossify.gallery.helpers.GET_IMAGE_INTENT
import org.fossify.gallery.helpers.GET_VIDEO_INTENT
import org.fossify.gallery.helpers.GROUP_BY_DATE_TAKEN_DAILY
import org.fossify.gallery.helpers.GROUP_BY_DATE_TAKEN_MONTHLY
import org.fossify.gallery.helpers.GROUP_BY_LAST_MODIFIED_DAILY
import org.fossify.gallery.helpers.GROUP_BY_LAST_MODIFIED_MONTHLY
import org.fossify.gallery.helpers.GROUP_DESCENDING
import org.fossify.gallery.helpers.LOCATION_INTERNAL
import org.fossify.gallery.helpers.MAX_COLUMN_COUNT
import org.fossify.gallery.helpers.MONTH_MILLISECONDS
import org.fossify.gallery.helpers.MediaFetcher
import org.fossify.gallery.helpers.PICKED_PATHS
import org.fossify.gallery.helpers.RECYCLE_BIN
import org.fossify.gallery.helpers.SET_WALLPAPER_INTENT
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.TEMP_DELETE_OPTIONS_DURATION
import org.fossify.gallery.helpers.SKIP_AUTHENTICATION
import org.fossify.gallery.helpers.getPermissionToRequest
import org.fossify.gallery.helpers.getPermissionsToRequest
import org.fossify.gallery.interfaces.DirectoryOperationsListener
import org.fossify.gallery.jobs.NewPhotoFetcher
import org.fossify.gallery.models.Directory
import org.fossify.gallery.models.Medium
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

class MainActivity : SimpleActivity(), DirectoryOperationsListener {
    override var isSearchBarEnabled = true

    companion object {
        private const val PICK_MEDIA = 2
        private const val PICK_WALLPAPER = 3
        private const val LAST_MEDIA_CHECK_PERIOD = 3000L
    }

    private var mIsPickImageIntent = false
    private var mIsPickVideoIntent = false
    private var mIsGetImageContentIntent = false
    private var mIsGetVideoContentIntent = false
    private var mIsGetAnyContentIntent = false
    private var mIsSetWallpaperIntent = false
    private var mAllowPickingMultiple = false
    private var mIsThirdPartyIntent = false
    private var mIsGettingDirs = false
    private var mLoadedInitialPhotos = false
    private var mShouldStopFetching = false
    private var mWasDefaultFolderChecked = false
    private var mWasMediaManagementPromptShown = false
    private var mLatestMediaId = 0L
    private var mLatestMediaDateId = 0L

    // used at "Group direct subfolders" for navigation
    private var mCurrentPathPrefix = ""

    // used at "Group direct subfolders" for navigating Up with the back button
    private var mOpenedSubfolders = arrayListOf("")

    private var mDateFormat = ""
    private var mTimeFormat = ""
    private var mLastMediaHandler = Handler()
    private var mTemporaryDeleteOptionsHandler = Handler()
    private var mZoomListener: MyRecyclerView.MyZoomListener? = null
    private var mLastMediaFetcher: MediaFetcher? = null
    private var mDirs = ArrayList<Directory>()
    private var mDirsIgnoringSearch = ArrayList<Directory>()

    private var mStoredAnimateGifs = true
    private var mStoredCropThumbnails = true
    private var mStoredScrollHorizontally = true
    private var mStoredTextColor = 0
    private var mStoredPrimaryColor = 0
    private var mStoredStyleString = ""
    private val binding by viewBinding(ActivityMainBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (config.defaultFolder == "favorites") config.defaultFolder = ""
        config.removePinnedFolders(hashSetOf("favorites"))
        config.everShownFolders = config.everShownFolders - "favorites"
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)

        if (savedInstanceState == null) {
            config.tempSkipDeleteConfirmation = false
            config.tempSkipRecycleBin = false

            checkRecycleBinItems()
            startNewPhotoFetcher()
        }

        mIsPickImageIntent = isPickImageIntent(intent)
        mIsPickVideoIntent = isPickVideoIntent(intent)
        mIsGetImageContentIntent = isGetImageContentIntent(intent)
        mIsGetVideoContentIntent = isGetVideoContentIntent(intent)
        mIsGetAnyContentIntent = isGetAnyContentIntent(intent)
        mIsSetWallpaperIntent = isSetWallpaperIntent(intent)
        mAllowPickingMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        mIsThirdPartyIntent = mIsPickImageIntent
                || mIsPickVideoIntent
                || mIsGetImageContentIntent
                || mIsGetVideoContentIntent
                || mIsGetAnyContentIntent
                || mIsSetWallpaperIntent

        setupOptionsMenu()
        binding.createFolder.setOnClickListener { createNewFolder() }
        refreshMenuItems()

        setupEdgeToEdge(
            padBottomImeAndSystem = listOf(binding.directoriesGrid)
        )

        binding.directoriesRefreshLayout.setOnRefreshListener { getDirectories() }
        storeStateVariables()
        checkWhatsNewDialog()
        setupLatestMediaId()

        if (!config.wasRecycleBinPinned) {
            config.addPinnedFolders(hashSetOf(RECYCLE_BIN))
            config.wasRecycleBinPinned = true
            config.saveFolderGrouping(SHOW_ALL, GROUP_BY_DATE_TAKEN_DAILY or GROUP_DESCENDING)
        }

        if (!config.wasSortingByNumericValueAdded) {
            config.wasSortingByNumericValueAdded = true
            config.sorting = config.sorting or SORT_USE_NUMERIC_VALUE
        }

        updateWidgets()
        registerFileUpdateListener()

        binding.directoriesSwitchSearching.setOnClickListener {
            launchSearchActivity()
        }

        // just request the permission, tryLoadGallery will then trigger in onResume
        handleMediaPermissions()
    }

    private fun handleMediaPermissions(callback: (() -> Unit)? = null) {
        requestMediaPermissions(enableRationale = true) {
            callback?.invoke()
            if (isRPlus() && !mWasMediaManagementPromptShown) {
                mWasMediaManagementPromptShown = true
                handleMediaManagementPrompt { }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mTemporaryDeleteOptionsHandler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        updateMenuColors()
        config.isThirdPartyIntent = false
        mDateFormat = config.dateFormat
        mTimeFormat = getTimeFormat()

        refreshMenuItems()

        if (mStoredAnimateGifs != config.animateGifs) {
            getRecyclerAdapter()?.updateAnimateGifs(config.animateGifs)
        }

        if (mStoredCropThumbnails != config.cropThumbnails) {
            getRecyclerAdapter()?.updateCropThumbnails(config.cropThumbnails)
        }

        if (mStoredScrollHorizontally != false) {
            mLoadedInitialPhotos = false
            binding.directoriesGrid.adapter = null
            getDirectories()
        }

        if (mStoredTextColor != getProperTextColor()) {
            getRecyclerAdapter()?.updateTextColor(getProperTextColor())
        }

        val primaryColor = getProperPrimaryColor()
        if (mStoredPrimaryColor != primaryColor) {
            getRecyclerAdapter()?.updatePrimaryColor()
        }

        val styleString =
            "${config.folderStyle}${config.showFolderMediaCount}${config.limitFolderTitle}"
        if (mStoredStyleString != styleString) {
            setupAdapter(mDirs, forceRecreate = true)
        }

        binding.directoriesFastscroller.updateColors(primaryColor)
        binding.directoriesRefreshLayout.isEnabled = false
        getRecyclerAdapter()?.apply {
            dateFormat = config.dateFormat
            timeFormat = getTimeFormat()
        }

        binding.directoriesEmptyPlaceholder.setTextColor(getProperTextColor())
        binding.directoriesEmptyPlaceholder2.setTextColor(primaryColor)
        binding.directoriesSwitchSearching.setTextColor(primaryColor)
        binding.directoriesSwitchSearching.underlineText()
        binding.directoriesEmptyPlaceholder2.bringToFront()

        if (!binding.mainMenu.isSearchOpen) {
            refreshMenuItems()
            tryLoadGallery()
        }

        if (config.searchAllFilesByDefault) {
            binding.mainMenu.updateHintText(getString(org.fossify.commons.R.string.search_files))
        } else {
            binding.mainMenu.updateHintText(getString(org.fossify.commons.R.string.search_folders))
        }
    }

    override fun onPause() {
        super.onPause()
        binding.directoriesRefreshLayout.isRefreshing = false
        mIsGettingDirs = false
        storeStateVariables()
        mLastMediaHandler.removeCallbacksAndMessages(null)
    }

    override fun onStop() {
        super.onStop()

        if (config.tempSkipDeleteConfirmation || config.tempSkipRecycleBin) {
            mTemporaryDeleteOptionsHandler.postDelayed({
                config.tempSkipDeleteConfirmation = false
                config.tempSkipRecycleBin = false
            }, TEMP_DELETE_OPTIONS_DURATION)
        } else {
            mTemporaryDeleteOptionsHandler.removeCallbacksAndMessages(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            config.tempSkipDeleteConfirmation = false
            config.tempSkipRecycleBin = false
            mTemporaryDeleteOptionsHandler.removeCallbacksAndMessages(null)

            unregisterFileUpdateListener()

            mLastMediaFetcher?.shouldStop = true
            GalleryDatabase.destroyInstance()

        }
    }

    override fun onBackPressedCompat(): Boolean {
        return if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
            true
        } else if (config.groupDirectSubfolders) {
            if (mCurrentPathPrefix.isEmpty()) {
                appLockManager.lock()
                false
            } else {
                mOpenedSubfolders.removeAt(mOpenedSubfolders.lastIndex)
                mCurrentPathPrefix = mOpenedSubfolders.last()
                setupAdapter(mDirs)
                true
            }
        } else {
            appLockManager.lock()
            false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (resultCode == RESULT_OK) {
            if (requestCode == PICK_MEDIA && resultData != null) {
                val resultIntent = Intent()
                var resultUri: Uri? = null
                if (mIsThirdPartyIntent) {
                    when {
                        intent.extras?.containsKey(MediaStore.EXTRA_OUTPUT) == true
                                && intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 -> {
                            resultUri = fillExtraOutput(resultData)
                        }

                        resultData.extras?.containsKey(PICKED_PATHS) == true -> {
                            fillPickedPaths(resultData, resultIntent)
                        }

                        else -> fillIntentPath(resultData, resultIntent)
                    }
                }

                if (resultUri != null) {
                    resultIntent.data = resultUri
                    resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                setResult(RESULT_OK, resultIntent)
                finish()
            } else if (requestCode == PICK_WALLPAPER) {
                setResult(RESULT_OK)
                finish()
            }
        }
        super.onActivityResult(requestCode, resultCode, resultData)
    }

    private fun refreshMenuItems() {
        binding.groupSubfolders.setOnCheckedChangeListener(null)
        binding.groupSubfolders.isChecked = config.groupDirectSubfolders
        binding.groupSubfolders.setOnCheckedChangeListener { _, checked ->
            config.groupDirectSubfolders = checked
            mCurrentPathPrefix = ""
            binding.directoriesGrid.adapter = null
            getDirectories()
        }
    }

    private fun setupOptionsMenu() {
        val menuId = if (mIsThirdPartyIntent) {
            R.menu.menu_main_intent
        } else {
            R.menu.menu_main
        }

        binding.mainMenu.requireToolbar().inflateMenu(menuId)
        binding.mainMenu.toggleHideOnScroll(!false)
        binding.mainMenu.setupMenu()

        binding.mainMenu.onSearchTextChangedListener = { text ->
            setupAdapter(mDirsIgnoringSearch, text)
            binding.directoriesRefreshLayout.isEnabled =
                text.isEmpty() && false
            binding.directoriesSwitchSearching.beVisibleIf(text.isNotEmpty())
        }

        binding.mainMenu.requireToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.sort -> showSortingDialog()
                R.id.column_count -> changeColumnCount()
                R.id.settings -> launchSettings()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun updateMenuColors() {
        binding.mainMenu.updateColors()
    }

    private fun getRecyclerAdapter() = binding.directoriesGrid.adapter as? DirectoryAdapter

    private fun storeStateVariables() {
        mStoredTextColor = getProperTextColor()
        mStoredPrimaryColor = getProperPrimaryColor()
        config.apply {
            mStoredAnimateGifs = animateGifs
            mStoredCropThumbnails = cropThumbnails
            mStoredScrollHorizontally = scrollHorizontally
            mStoredStyleString = "$folderStyle$showFolderMediaCount$limitFolderTitle"
        }
    }

    private fun startNewPhotoFetcher() {
        val photoFetcher = NewPhotoFetcher()
        if (!photoFetcher.isScheduled(applicationContext)) {
            photoFetcher.scheduleJob(applicationContext)
        }
    }

    private fun checkOTGPath() {
        ensureBackgroundThread {
            if (!config.wasOTGHandled && hasPermission(getPermissionToRequest()) && hasOTGConnected() && config.OTGPath.isEmpty()) {
                getStorageDirectories().firstOrNull {
                    it.trimEnd('/') != internalStoragePath
                            && it.trimEnd('/') != sdCardPath
                }?.apply {
                    config.wasOTGHandled = true
                    val otgPath = trimEnd('/')
                    config.OTGPath = otgPath
                    config.addIncludedFolder(otgPath)
                }
            }
        }
    }

    private fun tryLoadGallery() {
        // avoid calling anything right after granting the permission, it will be called from onResume()
        val wasMissingPermission =
            config.appRunCount == 1 && !hasAllPermissions(getPermissionsToRequest())
        handleMediaPermissions {
            if (wasMissingPermission) {
                return@handleMediaPermissions
            }

            if (!mWasDefaultFolderChecked) {
                openDefaultFolder()
                mWasDefaultFolderChecked = true
            }

            checkOTGPath()

            getDirectories()

            setupLayoutManager()
        }
    }

    private fun getDirectories() {
        if (mIsGettingDirs) {
            return
        }

        mShouldStopFetching = true
        mIsGettingDirs = true
        val getImages = mIsPickImageIntent || mIsGetImageContentIntent
        val getVideos = mIsPickVideoIntent || mIsGetVideoContentIntent

        getCachedDirectories(getVideos && !getImages, getImages && !getVideos) {
            gotDirectories(addTempFolderIfNeeded(it))
        }
    }

    private fun launchSearchActivity() {
        hideKeyboard()
        Intent(this, SearchActivity::class.java).apply {
            startActivity(this)
        }

        binding.mainMenu.postDelayed({
            binding.mainMenu.closeSearch()
        }, 500)
    }

    private fun showSortingDialog() {
        ChangeSortingDialog(this, true, false) {
            binding.directoriesGrid.adapter = null
            if (config.directorySorting and SORT_BY_DATE_MODIFIED != 0 || config.directorySorting and SORT_BY_DATE_TAKEN != 0) {
                getDirectories()
            } else {
                ensureBackgroundThread {
                    gotDirectories(getCurrentlyDisplayedDirs())
                }
            }

            getRecyclerAdapter()?.directorySorting = config.directorySorting
        }
    }

    override fun deleteFolders(folders: ArrayList<File>) {
        val fileDirItems = folders
            .asSequence()
            .filter { it.isDirectory }
            .map { FileDirItem(it.absolutePath, it.name, true) }
            .toMutableList() as ArrayList<FileDirItem>

        when {
            fileDirItems.isEmpty() -> return
            fileDirItems.size == 1 -> {
                try {
                    toast(
                        String.format(
                            getString(org.fossify.commons.R.string.deleting_folder),
                            fileDirItems.first().name
                        )
                    )
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }

            else -> {
                val baseString = if (config.useRecycleBin && !config.tempSkipRecycleBin) {
                    org.fossify.commons.R.plurals.moving_items_into_bin
                } else {
                    org.fossify.commons.R.plurals.delete_items
                }

                toast(
                    msg = resources.getQuantityString(
                        baseString, fileDirItems.size, fileDirItems.size
                    )
                )
            }
        }

        val itemsToDelete = ArrayList<FileDirItem>()

        val showHidden = config.shouldShowHidden
        fileDirItems.filter { it.isDirectory }.forEach {
            val files = File(it.path).listFiles()
            files?.filter {
                it.absolutePath.isMediaFile()
                        && (showHidden || !it.name.startsWith('.'))
            }?.mapTo(itemsToDelete) { it.toFileDirItem(applicationContext) }
        }

        if (config.useRecycleBin && !config.tempSkipRecycleBin) {
            val pathsToDelete = ArrayList<String>()
            itemsToDelete.mapTo(pathsToDelete) { it.path }

            movePathsInRecycleBin(pathsToDelete) {
                if (it) {
                    deleteFilteredFileDirItems(itemsToDelete, folders)
                } else {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                }
            }
        } else {
            deleteFilteredFileDirItems(itemsToDelete, folders)
        }
    }

    private fun deleteFilteredFileDirItems(
        fileDirItems: ArrayList<FileDirItem>,
        folders: ArrayList<File>
    ) {
        val OTGPath = config.OTGPath
        deleteFiles(fileDirItems) {
            runOnUiThread {
                refreshItems()
            }

            ensureBackgroundThread {
                folders.filter { !getDoesFilePathExist(it.absolutePath, OTGPath) }.forEach {
                    directoryDB.deleteDirPath(it.absolutePath)
                }

            }
        }
    }

    private fun setupLayoutManager() {

            setupGridLayoutManager()

        (binding.directoriesRefreshLayout.layoutParams as RelativeLayout.LayoutParams)
            .addRule(RelativeLayout.BELOW, R.id.directories_switch_searching)
    }

    private fun setupGridLayoutManager() {
        val layoutManager = binding.directoriesGrid.layoutManager as MyGridLayoutManager

            layoutManager.orientation = RecyclerView.VERTICAL
            binding.directoriesRefreshLayout.layoutParams =
                RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )

        layoutManager.spanCount = config.dirColumnCnt
    }

    private fun setupListLayoutManager() {
        val layoutManager = binding.directoriesGrid.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = 1
        layoutManager.orientation = RecyclerView.VERTICAL
        binding.directoriesRefreshLayout.layoutParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        mZoomListener = null
    }

    private fun initZoomListener() {

            val layoutManager = binding.directoriesGrid.layoutManager as MyGridLayoutManager
            mZoomListener = object : MyRecyclerView.MyZoomListener {
                override fun zoomIn() {
                    if (layoutManager.spanCount > 1) {
                        reduceColumnCount()
                        getRecyclerAdapter()?.finishActMode()
                    }
                }

                override fun zoomOut() {
                    if (layoutManager.spanCount < MAX_COLUMN_COUNT) {
                        increaseColumnCount()
                        getRecyclerAdapter()?.finishActMode()
                    }
                }
            }

    }

    private fun createNewFolder() {
        FilePickerDialog(this, internalStoragePath, false, config.shouldShowHidden, false, true) {
            CreateNewFolderDialog(this, it) {
                config.tempFolderPath = it
                ensureBackgroundThread {
                    gotDirectories(addTempFolderIfNeeded(getCurrentlyDisplayedDirs()))
                }
            }
        }
    }

    private fun changeColumnCount() {
        val items = ArrayList<RadioItem>()
        for (i in 1..MAX_COLUMN_COUNT) {
            items.add(
                RadioItem(
                    id = i,
                    title = resources.getQuantityString(
                        org.fossify.commons.R.plurals.column_counts, i, i
                    )
                )
            )
        }

        val currentColumnCount =
            (binding.directoriesGrid.layoutManager as MyGridLayoutManager).spanCount
        RadioGroupDialog(this, items, currentColumnCount) {
            val newColumnCount = it as Int
            if (currentColumnCount != newColumnCount) {
                config.dirColumnCnt = newColumnCount
                columnCountChanged()
            }
        }
    }

    private fun increaseColumnCount() {
        config.dirColumnCnt += 1
        columnCountChanged()
    }

    private fun reduceColumnCount() {
        config.dirColumnCnt -= 1
        columnCountChanged()
    }

    private fun columnCountChanged() {
        (binding.directoriesGrid.layoutManager as MyGridLayoutManager).spanCount =
            config.dirColumnCnt
        refreshMenuItems()
        getRecyclerAdapter()?.apply {
            notifyItemRangeChanged(0, dirs.size)
        }
    }

    private fun isPickImageIntent(intent: Intent): Boolean {
        return isPickIntent(intent) && (hasImageContentData(intent) || isImageType(intent))
    }

    private fun isPickVideoIntent(intent: Intent): Boolean {
        return isPickIntent(intent) && (hasVideoContentData(intent) || isVideoType(intent))
    }

    private fun isPickIntent(intent: Intent): Boolean {
        return intent.action == Intent.ACTION_PICK
    }

    private fun isGetContentIntent(intent: Intent): Boolean {
        return intent.action == Intent.ACTION_GET_CONTENT && intent.type != null
    }

    private fun anyExtraMimeTypeStartingWith(intent: Intent, mimeTypePrefix: String): Boolean {
        return intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
            ?.any { it.startsWith(mimeTypePrefix) } == true
    }

    private fun isGetImageContentIntent(intent: Intent): Boolean {
        return isGetContentIntent(intent)
                && (intent.type!!.startsWith("image/")
                || intent.type == Images.Media.CONTENT_TYPE
                || anyExtraMimeTypeStartingWith(intent, "image/"))
    }

    private fun isGetVideoContentIntent(intent: Intent): Boolean {
        return isGetContentIntent(intent)
                && (intent.type!!.startsWith("video/")
                || intent.type == Video.Media.CONTENT_TYPE
                || anyExtraMimeTypeStartingWith(intent, "video/"))
    }

    private fun isGetAnyContentIntent(intent: Intent): Boolean {
        return isGetContentIntent(intent) && intent.type == "*/*"
    }

    private fun isSetWallpaperIntent(intent: Intent?): Boolean {
        return intent?.action == Intent.ACTION_SET_WALLPAPER
    }

    private fun hasImageContentData(intent: Intent): Boolean {
        return intent.data == Images.Media.EXTERNAL_CONTENT_URI
                || intent.data == Images.Media.INTERNAL_CONTENT_URI
    }

    private fun hasVideoContentData(intent: Intent): Boolean {
        return intent.data == Video.Media.EXTERNAL_CONTENT_URI
                || intent.data == Video.Media.INTERNAL_CONTENT_URI
    }

    private fun isImageType(intent: Intent): Boolean {
        return (intent.type?.startsWith("image/") == true
                || intent.type == Images.Media.CONTENT_TYPE)
    }

    private fun isVideoType(intent: Intent): Boolean {
        return (intent.type?.startsWith("video/") == true
                || intent.type == Video.Media.CONTENT_TYPE)
    }

    private fun fillExtraOutput(resultData: Intent): Uri? {
        val file = File(resultData.data!!.path!!)
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        try {
            val output = intent.extras!!.get(MediaStore.EXTRA_OUTPUT) as Uri
            inputStream = FileInputStream(file)
            outputStream = contentResolver.openOutputStream(output)
            inputStream.copyTo(outputStream!!)
        } catch (e: SecurityException) {
            showErrorToast(e)
        } catch (ignored: FileNotFoundException) {
            return getFilePublicUri(file, BuildConfig.APPLICATION_ID)
        } finally {
            inputStream?.close()
            outputStream?.close()
        }

        return null
    }

    private fun fillPickedPaths(resultData: Intent, resultIntent: Intent) {
        val paths = resultData.extras!!.getStringArrayList(PICKED_PATHS)
        val uris = paths!!
            .map { getFilePublicUri(File(it), BuildConfig.APPLICATION_ID) } as ArrayList
        val clipData = ClipData(
            "Attachment",
            arrayOf("image/*", "video/*"),
            ClipData.Item(uris.removeAt(0))
        )

        uris.forEach {
            clipData.addItem(ClipData.Item(it))
        }

        resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        resultIntent.clipData = clipData
    }

    private fun fillIntentPath(resultData: Intent, resultIntent: Intent) {
        val data = resultData.data
        val path = if (data.toString().startsWith("/")) data.toString() else data!!.path
        val uri = getFilePublicUri(File(path!!), BuildConfig.APPLICATION_ID)
        val type = path.getMimeType()
        resultIntent.setDataAndTypeAndNormalize(uri, type)
        resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun itemClicked(path: String) {
        handleLockedFolderOpening(path) { success ->
            if (success) {
                Intent(this, MediaActivity::class.java).apply {
                    putExtra(SKIP_AUTHENTICATION, true)
                    putExtra(DIRECTORY, path)
                    handleMediaIntent(this)
                }
            }
        }
    }

    private fun handleMediaIntent(intent: Intent) {
        hideKeyboard()
        intent.apply {
            if (mIsSetWallpaperIntent) {
                putExtra(SET_WALLPAPER_INTENT, true)
                startActivityForResult(this, PICK_WALLPAPER)
            } else {
                putExtra(GET_IMAGE_INTENT, mIsPickImageIntent || mIsGetImageContentIntent)
                putExtra(GET_VIDEO_INTENT, mIsPickVideoIntent || mIsGetVideoContentIntent)
                putExtra(GET_ANY_INTENT, mIsGetAnyContentIntent)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, mAllowPickingMultiple)
                startActivityForResult(this, PICK_MEDIA)
            }
        }
    }

    private fun gotDirectories(newDirs: ArrayList<Directory>) {
        mIsGettingDirs = false
        mShouldStopFetching = false

        val dirs = getSortedDirectories(newDirs)
        if (config.groupDirectSubfolders) {
            mDirs = dirs.clone() as ArrayList<Directory>
        }

        var isPlaceholderVisible = dirs.isEmpty()

        runOnUiThread {
            checkPlaceholderVisibility(dirs)
            setupAdapter(dirs.clone() as ArrayList<Directory>)
        }

        // cached folders have been loaded, recheck folders one by one starting with the first displayed
        mLastMediaFetcher?.shouldStop = true
        mLastMediaFetcher = MediaFetcher(applicationContext)
        val getImages = mIsPickImageIntent || mIsGetImageContentIntent
        val getVideos = mIsPickVideoIntent || mIsGetVideoContentIntent
        val getImagesOnly = getImages && !getVideos
        val getVideosOnly = getVideos && !getImages
        val hiddenString = getString(R.string.hidden)
        val albumCovers = config.parseAlbumCovers()
        val includedFolders = config.includedFolders
        val noMediaFolders = getNoMediaFoldersSync()
        val tempFolderPath = config.tempFolderPath
        val getProperFileSize = config.directorySorting and SORT_BY_SIZE != 0
        val dirPathsToRemove = ArrayList<String>()
        val lastModifieds = mLastMediaFetcher!!.getLastModifieds()
        val dateTakens = mLastMediaFetcher!!.getDateTakens()

        if (
            config.showRecycleBinAtFolders
            && !config.showRecycleBinLast
            && !dirs.map { it.path }.contains(RECYCLE_BIN)
        ) {
            try {
                if (mediaDB.getDeletedMediaCount() > 0) {
                    val recycleBin = Directory().apply {
                        path = RECYCLE_BIN
                        name = getString(org.fossify.commons.R.string.recycle_bin)
                        location = LOCATION_INTERNAL
                    }

                    dirs.add(0, recycleBin)
                }
            } catch (ignored: Exception) {
            }
        }

        // fetch files from MediaStore only, unless the app has the MANAGE_EXTERNAL_STORAGE permission on Android 11+
        val android11Files = mLastMediaFetcher?.getAndroid11FolderMedia(
            isPickImage = getImagesOnly,
            isPickVideo = getVideosOnly,
            getProperDateTaken = true,
            dateTakens = dateTakens
        )
        try {
            for (directory in dirs) {
                if (mShouldStopFetching || isDestroyed || isFinishing) {
                    return
                }

                val sorting = config.getFolderSorting(directory.path)
                val grouping = config.getFolderGrouping(directory.path)
                val getProperDateTaken = config.directorySorting and SORT_BY_DATE_TAKEN != 0
                        || sorting and SORT_BY_DATE_TAKEN != 0
                        || grouping and GROUP_BY_DATE_TAKEN_DAILY != 0
                        || grouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0

                val getProperLastModified =
                    config.directorySorting and SORT_BY_DATE_MODIFIED != 0
                            || sorting and SORT_BY_DATE_MODIFIED != 0
                            || grouping and GROUP_BY_LAST_MODIFIED_DAILY != 0
                            || grouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0

                val curMedia = mLastMediaFetcher!!.getFilesFrom(
                    curPath = directory.path,
                    isPickImage = getImagesOnly,
                    isPickVideo = getVideosOnly,
                    getProperDateTaken = getProperDateTaken,
                    getProperLastModified = getProperLastModified,
                    getProperFileSize = getProperFileSize,
                    getVideoDurations = false,
                    lastModifieds = lastModifieds,
                    dateTakens = dateTakens,
                    android11Files = android11Files
                )

                val newDir = if (curMedia.isEmpty()) {
                    if (directory.path != tempFolderPath) {
                        dirPathsToRemove.add(directory.path)
                    }
                    directory
                } else {
                    createDirectoryFromMedia(
                        path = directory.path,
                        curMedia = curMedia,
                        albumCovers = albumCovers,
                        hiddenString = hiddenString,
                        includedFolders = includedFolders,
                        getProperFileSize = getProperFileSize,
                        noMediaFolders = noMediaFolders
                    )
                }

                // we are looping through the already displayed folders looking for changes, do not do anything if nothing changed
                if (directory.copy(subfoldersCount = 0, subfoldersMediaCount = 0) == newDir) {
                    continue
                }

                directory.apply {
                    tmb = newDir.tmb
                    name = newDir.name
                    mediaCnt = newDir.mediaCnt
                    modified = newDir.modified
                    taken = newDir.taken
                    this@apply.size = newDir.size
                    types = newDir.types
                    sortValue = getDirectorySortingValue(curMedia, path, name, size, mediaCnt)
                }

                setupAdapter(dirs)

                // update directories and media files in the local db, delete invalid items. Intentionally creating a new thread
                updateDBDirectory(directory)
                if (!directory.isRecycleBin()) {
                    Thread {
                        try {
                            mediaDB.insertAll(curMedia)
                        } catch (ignored: Exception) {
                        }
                    }.start()
                }

                if (!directory.isRecycleBin()) {
                    getCachedMedia(directory.path, getVideosOnly, getImagesOnly) {
                        val mediaToDelete = ArrayList<Medium>()
                        it.forEach {
                            if (!curMedia.contains(it)) {
                                val medium = it as? Medium
                                val path = medium?.path
                                if (path != null) {
                                    mediaToDelete.add(medium)
                                }
                            }
                        }
                        mediaDB.deleteMedia(*mediaToDelete.toTypedArray())
                    }
                }
            }

            if (dirPathsToRemove.isNotEmpty()) {
                val dirsToRemove = dirs.filter { dirPathsToRemove.contains(it.path) }
                dirsToRemove.forEach {
                    directoryDB.deleteDirPath(it.path)
                }
                dirs.removeAll(dirsToRemove)
                setupAdapter(dirs)
            }
        } catch (ignored: Exception) {
        }

        val foldersToScan = mLastMediaFetcher!!.getFoldersToScan()
        if (config.showRecycleBinAtFolders) {
            if (foldersToScan.contains(RECYCLE_BIN)) {
                foldersToScan.remove(RECYCLE_BIN)
                foldersToScan.add(0, RECYCLE_BIN)
            } else {
                foldersToScan.add(0, RECYCLE_BIN)
            }
        } else {
            foldersToScan.remove(RECYCLE_BIN)
        }

        dirs.filterNot { it.path == RECYCLE_BIN }.forEach {
            foldersToScan.remove(it.path)
        }

        // check the remaining folders which were not cached at all yet
        for (folder in foldersToScan) {
            if (mShouldStopFetching || isDestroyed || isFinishing) {
                return
            }

            val sorting = config.getFolderSorting(folder)
            val grouping = config.getFolderGrouping(folder)
            val getProperDateTaken = config.directorySorting and SORT_BY_DATE_TAKEN != 0
                    || sorting and SORT_BY_DATE_TAKEN != 0
                    || grouping and GROUP_BY_DATE_TAKEN_DAILY != 0
                    || grouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0

            val getProperLastModified = config.directorySorting and SORT_BY_DATE_MODIFIED != 0
                    || sorting and SORT_BY_DATE_MODIFIED != 0
                    || grouping and GROUP_BY_LAST_MODIFIED_DAILY != 0
                    || grouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0

            val newMedia = mLastMediaFetcher!!.getFilesFrom(
                curPath = folder,
                isPickImage = getImagesOnly,
                isPickVideo = getVideosOnly,
                getProperDateTaken = getProperDateTaken,
                getProperLastModified = getProperLastModified,
                getProperFileSize = getProperFileSize,
                getVideoDurations = false,
                lastModifieds = lastModifieds,
                dateTakens = dateTakens,
                android11Files = android11Files
            )

            if (newMedia.isEmpty()) {
                continue
            }

            if (isPlaceholderVisible) {
                isPlaceholderVisible = false
                runOnUiThread {
                    binding.directoriesEmptyPlaceholder.beGone()
                    binding.directoriesEmptyPlaceholder2.beGone()
                    binding.directoriesFastscroller.beVisible()
                }
            }

            val newDir = createDirectoryFromMedia(
                path = folder,
                curMedia = newMedia,
                albumCovers = albumCovers,
                hiddenString = hiddenString,
                includedFolders = includedFolders,
                getProperFileSize = getProperFileSize,
                noMediaFolders = noMediaFolders
            )
            dirs.add(newDir)
            setupAdapter(dirs)

            // make sure to create a new thread for these operations, dont just use the common bg thread
            Thread {
                try {
                    directoryDB.insert(newDir)
                    if (folder != RECYCLE_BIN) {
                        mediaDB.insertAll(newMedia)
                    }
                } catch (ignored: Exception) {
                }
            }.start()
        }

        mLoadedInitialPhotos = true
        if (config.appRunCount > 1) {
            checkLastMediaChanged()
        }

        runOnUiThread {
            binding.directoriesRefreshLayout.isRefreshing = false
            checkPlaceholderVisibility(dirs)
        }

        checkInvalidDirectories(dirs)
        if (mDirs.size > 50) {

        }

        val everShownFolders = config.everShownFolders.toMutableSet() as HashSet<String>

        dirs.mapTo(everShownFolders) { it.path }

        try {
            // scan the internal storage from time to time for new folders
            if (config.appRunCount == 1 || config.appRunCount % 30 == 0) {
                everShownFolders.addAll(getFoldersWithMedia(config.internalStoragePath))
            }

            // catch some extreme exceptions like too many everShownFolders for storing, shouldnt really happen
            config.everShownFolders = everShownFolders
        } catch (e: Exception) {
            config.everShownFolders = HashSet()
        }

        mDirs = dirs.clone() as ArrayList<Directory>
    }

    private fun openDefaultFolder() {
        if (config.defaultFolder.isEmpty()) {
            return
        }

        val defaultDir = File(config.defaultFolder)

        if ((!defaultDir.exists() || !defaultDir.isDirectory) && (config.defaultFolder != RECYCLE_BIN)) {
            config.defaultFolder = ""
            return
        }

        Intent(this, MediaActivity::class.java).apply {
            putExtra(DIRECTORY, config.defaultFolder)
            handleMediaIntent(this)
        }
    }

    private fun checkPlaceholderVisibility(dirs: ArrayList<Directory>) {
        binding.directoriesEmptyPlaceholder.beVisibleIf(dirs.isEmpty() && mLoadedInitialPhotos)
        binding.directoriesEmptyPlaceholder2.beVisibleIf(dirs.isEmpty() && mLoadedInitialPhotos)

        if (binding.mainMenu.isSearchOpen) {
            binding.directoriesEmptyPlaceholder.text =
                getString(org.fossify.commons.R.string.no_items_found)
            binding.directoriesEmptyPlaceholder2.beGone()
        } else if (dirs.isEmpty()) {
            if (isRPlus() && !isExternalStorageManager()) {
                binding.directoriesEmptyPlaceholder.text =
                    getString(org.fossify.commons.R.string.no_items_found)
                binding.directoriesEmptyPlaceholder2.beGone()
            } else {
                binding.directoriesEmptyPlaceholder.text = getString(R.string.no_media_add_included)
                binding.directoriesEmptyPlaceholder2.text = getString(R.string.add_folder)
            }

            binding.directoriesEmptyPlaceholder2.setOnClickListener {
                showAddIncludedFolderDialog {
                    refreshItems()
                }
            }
        }

        binding.directoriesEmptyPlaceholder2.underlineText()
        binding.directoriesFastscroller.beVisibleIf(binding.directoriesEmptyPlaceholder.isGone())
    }

    private fun setupAdapter(
        dirs: ArrayList<Directory>,
        textToSearch: String = binding.mainMenu.getCurrentQuery(),
        forceRecreate: Boolean = false
    ) {
        val currAdapter = binding.directoriesGrid.adapter
        val distinctDirs = dirs
            .distinctBy { it.path.getDistinctPath() }
            .toMutableList() as ArrayList<Directory>

        val sortedDirs = getSortedDirectories(distinctDirs)
        var dirsToShow = getDirsToShow(
            dirs = sortedDirs,
            allDirs = mDirs,
            currentPathPrefix = mCurrentPathPrefix
        ).clone() as ArrayList<Directory>

        if (currAdapter == null || forceRecreate) {
            mDirsIgnoringSearch = dirs
            initZoomListener()
            DirectoryAdapter(
                this,
                dirsToShow,
                this,
                binding.directoriesGrid,
                isPickIntent(intent) || isGetAnyContentIntent(intent),
                binding.directoriesRefreshLayout
            ) {
                val clickedDir = it as Directory
                val path = clickedDir.path
                if (clickedDir.subfoldersCount == 1 || !config.groupDirectSubfolders) {
                    if (path != config.tempFolderPath) {
                        itemClicked(path)
                    }
                } else {
                    mCurrentPathPrefix = path
                    mOpenedSubfolders.add(path)
                    setupAdapter(mDirs, "")
                }
            }.apply {
                setupZoomListener(mZoomListener)
                runOnUiThread {
                    binding.directoriesGrid.adapter = this
                    setupScrollDirection()

                    if (VIEW_TYPE_GRID == VIEW_TYPE_LIST && areSystemAnimationsEnabled) {
                        binding.directoriesGrid.scheduleLayoutAnimation()
                    }
                }
            }
        } else {
            runOnUiThread {
                if (textToSearch.isNotEmpty()) {
                    dirsToShow = dirsToShow
                        .filter { it.name.contains(textToSearch, true) }
                        .sortedBy { !it.name.startsWith(textToSearch, true) }
                        .toMutableList() as ArrayList
                }
                checkPlaceholderVisibility(dirsToShow)

                (binding.directoriesGrid.adapter as? DirectoryAdapter)?.updateDirs(dirsToShow)
            }
        }

        // recyclerview sometimes becomes empty at init/update, triggering an invisible refresh like this seems to work fine
        binding.directoriesGrid.postDelayed({
            binding.directoriesGrid.scrollBy(0, 0)
        }, 500)
    }

    private fun setupScrollDirection() {
        val scrollHorizontally =
            false && VIEW_TYPE_GRID == VIEW_TYPE_GRID
        binding.directoriesFastscroller.setScrollVertically(!scrollHorizontally)
    }

    private fun checkInvalidDirectories(dirs: ArrayList<Directory>) {
        val invalidDirs = ArrayList<Directory>()
        val OTGPath = config.OTGPath
        dirs.filter { !it.isRecycleBin() }.forEach {
            if (!getDoesFilePathExist(it.path, OTGPath)) {
                invalidDirs.add(it)
            } else if (it.path != config.tempFolderPath && (!isRPlus() || isExternalStorageManager())) {
                // avoid calling file.list() or listfiles() on Android 11+, it became way too slow
                val children = if (isPathOnOTG(it.path)) {
                    getOTGFolderChildrenNames(it.path)
                } else {
                    File(it.path).list()?.asList()
                }

                val hasMediaFile = children?.any {
                    it != null && (
                            it.isMediaFile()
                                    || (it.startsWith("img_", true)
                                    && File(it).isDirectory)
                            )
                } == true

                if (!hasMediaFile) {
                    invalidDirs.add(it)
                }
            }
        }

        if (config.useRecycleBin) {
            try {
                val binFolder = dirs.firstOrNull { it.path == RECYCLE_BIN }
                if (binFolder != null && mediaDB.getDeletedMedia().isEmpty()) {
                    invalidDirs.add(binFolder)
                }
            } catch (ignored: Exception) {
            }
        }

        if (invalidDirs.isNotEmpty()) {
            dirs.removeAll(invalidDirs)
            setupAdapter(dirs)
            invalidDirs.forEach {
                try {
                    directoryDB.deleteDirPath(it.path)
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun getCurrentlyDisplayedDirs() = getRecyclerAdapter()?.dirs ?: ArrayList()

    private fun setupLatestMediaId() {
        ensureBackgroundThread {
            if (hasPermission(PERMISSION_READ_STORAGE)) {
                mLatestMediaId = getLatestMediaId()
                mLatestMediaDateId = getLatestMediaByDateId()
            }
        }
    }

    private fun checkLastMediaChanged() {
        if (isDestroyed) {
            return
        }

        mLastMediaHandler.postDelayed({
            ensureBackgroundThread {
                val mediaId = getLatestMediaId()
                val mediaDateId = getLatestMediaByDateId()
                if (mLatestMediaId != mediaId || mLatestMediaDateId != mediaDateId) {
                    mLatestMediaId = mediaId
                    mLatestMediaDateId = mediaDateId
                    runOnUiThread {
                        getDirectories()
                    }
                } else {
                    mLastMediaHandler.removeCallbacksAndMessages(null)
                    checkLastMediaChanged()
                }
            }
        }, LAST_MEDIA_CHECK_PERIOD)
    }

    private fun checkRecycleBinItems() {
        if (config.useRecycleBin && config.lastBinCheck < System.currentTimeMillis() - DAY_SECONDS * 1000) {
            config.lastBinCheck = System.currentTimeMillis()
            Handler().postDelayed({
                ensureBackgroundThread {
                    try {
                        val filesToDelete = mediaDB.getOldRecycleBinItems(
                            System.currentTimeMillis() - MONTH_MILLISECONDS
                        )
                        filesToDelete.forEach {
                            if (File(it.path.replaceFirst(RECYCLE_BIN, recycleBinPath)).delete()) {
                                mediaDB.deleteMediumPath(it.path)
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
            }, 3000L)
        }
    }

    // exclude probably unwanted folders, for example facebook stickers are split between hundreds of separate folders like
    // /storage/emulated/0/Android/data/com.facebook.orca/files/stickers/175139712676531/209575122566323
    // /storage/emulated/0/Android/data/com.facebook.orca/files/stickers/497837993632037/499671223448714

    private fun getFoldersWithMedia(path: String): HashSet<String> {
        val folders = HashSet<String>()
        try {
            val files = File(path).listFiles()
            if (files != null) {
                files.sortBy { !it.isDirectory }
                for (file in files) {
                    if (file.isDirectory && !file.startsWith("${config.internalStoragePath}/Android")) {
                        folders.addAll(getFoldersWithMedia(file.absolutePath))
                    } else if (file.isFile && file.isMediaFile()) {
                        folders.add(file.parent ?: "")
                        break
                    }
                }
            }
        } catch (ignored: Exception) {
        }

        return folders
    }

    override fun refreshItems() {
        getDirectories()
    }

    override fun recheckPinnedFolders() {
        ensureBackgroundThread {
            gotDirectories(movePinnedDirectoriesToFront(getCurrentlyDisplayedDirs()))
        }
    }

    override fun updateDirectories(directories: ArrayList<Directory>) {
        ensureBackgroundThread {
            storeDirectoryItems(directories)
            removeInvalidDBDirectories()
        }
    }

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }
}
