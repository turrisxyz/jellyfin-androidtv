package org.jellyfin.androidtv.ui.startup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.TvApp
import org.jellyfin.androidtv.data.model.LoadingState
import org.jellyfin.androidtv.data.model.ServerList
import org.jellyfin.androidtv.data.model.User
import org.jellyfin.androidtv.ui.browsing.MainActivity
import org.jellyfin.androidtv.ui.itemdetail.FullDetailsActivity
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.apiclient.toUserDto
import org.jellyfin.apiclient.interaction.ApiClient
import org.jellyfin.apiclient.interaction.Response
import org.jellyfin.apiclient.model.dto.BaseItemDto
import org.koin.android.viewmodel.ext.android.viewModel
import org.koin.java.KoinJavaComponent.get
import timber.log.Timber

class StartupActivity : FragmentActivity() {
	private companion object {
		private const val NETWORK_PERMISSION = 1
		private const val ITEM_ID = "ItemId"
	}

	private var application: TvApp? = null
	private val loginViewModel: LoginViewModel by viewModel()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.fragment_content_view)
		supportFragmentManager.beginTransaction()
			.replace(R.id.content_view, SplashFragment())
			.commit()
		application = applicationContext as TvApp

		//Ensure basic permissions
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED
						|| ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED)) {
			Timber.i("Requesting network permissions")
			ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.INTERNET), NETWORK_PERMISSION)
		} else {
			Timber.i("Basic network permissions are granted")

			val loadingObserver = Observer<ServerList> { serverList ->
				Timber.d("LoadingState: %s", serverList.state.toString())
				if (serverList.state == LoadingState.SUCCESS) start()
			}
			loginViewModel.serverList.observe(this, loadingObserver)
		}

		// Navigate to home screen when user has logged in
		val currentUserObserver = Observer<User> { user: User? ->
			// TODO: This should be removed in favor of fragments getting the current user directly
			application!!.currentUser = user?.toUserDto()
			// User has been logged in continue to home screen
			// TODO: We should use a fragment for this, but the current fragment depends on BaseActivity
			val intent = Intent(application, MainActivity::class.java)
			startActivity(intent)
			finish()
		}
		loginViewModel.currentUser.observe(this, currentUserObserver)
	}

	private fun start() {
		if (application!!.currentUser != null && MediaManager.isPlayingAudio()) {
			openNextActivity()
		} else {
			//clear audio queue in case left over from last run
			MediaManager.clearAudioQueue()
			MediaManager.clearVideoQueue()
			establishConnection()
		}
	}

	private fun openNextActivity() {
		val itemId = intent.getStringExtra(ITEM_ID)
		val itemIsUserView = intent.getBooleanExtra("ItemIsUserView", false)
		if (itemId != null) {
			if (itemIsUserView) {
				get(ApiClient::class.java).GetItemAsync(itemId, get(ApiClient::class.java).currentUserId, object : Response<BaseItemDto?>() {
					override fun onResponse(item: BaseItemDto?) {
						ItemLauncher.launchUserView(item, this@StartupActivity, true)
					}

					override fun onError(exception: Exception) {
						// go straight into last connection
						val intent = Intent(application, MainActivity::class.java)
						startActivity(intent)
						finish()
					}
				})
			} else {
				//Can just go right into details
				val detailsIntent = Intent(this, FullDetailsActivity::class.java)
				detailsIntent.putExtra(ITEM_ID, intent.getStringExtra(ITEM_ID))
				startActivity(detailsIntent)
				finish()
			}
		} else {
			// go straight into last connection
			val intent = Intent(this, MainActivity::class.java)
			startActivity(intent)
			finish()
		}
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
		if (requestCode == NETWORK_PERMISSION) { // If request is cancelled, the result arrays are empty.
			if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				// permission was granted
				start()
			} else {
				// permission denied! Disable the app.
				Utils.showToast(this, "Application cannot continue without network")
				finish()
			}
		}
	}

	private fun establishConnection() {
		// Ask for server information
		supportFragmentManager.beginTransaction()
			.replace(R.id.content_view, StartupToolbarFragment(
				onAddServerClicked = {
					supportFragmentManager.beginTransaction()
						.replace(R.id.content_view, AddServerFragment(
							onConfirmCallback = {
								GlobalScope.launch { loginViewModel.connect(it) }
							},
							onClose = { supportFragmentManager.popBackStack() }
						))
						.addToBackStack(StartupToolbarFragment::class.simpleName)
						.commit()
				}
			))
			.add(R.id.content_view, ListServerFragment())
			.commit()
	}
}
