package org.jellyfin.androidtv.details.actions

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.TvApp
import org.jellyfin.androidtv.model.itemtypes.PlayableItem
import org.jellyfin.androidtv.util.apiclient.markPlayed
import org.jellyfin.androidtv.util.apiclient.markUnplayed

class ToggleWatchedAction(context: Context, val item: PlayableItem) : BaseAction(ActionID.TOGGLE_WATCHED.id, context) {
	init {
		label1 = context.getString(if (item.played) R.string.lbl_mark_unplayed else R.string.lbl_mark_played)
	}

	override fun onClick() {
		GlobalScope.launch(Dispatchers.Main) {
			val apiClient = TvApp.getApplication().apiClient

			if (item.played) apiClient.markUnplayed(item.id, TvApp.getApplication().currentUser.id)
			else apiClient.markPlayed(item.id, TvApp.getApplication().currentUser.id, null)

			item.played = !item.played
		}
	}
}
