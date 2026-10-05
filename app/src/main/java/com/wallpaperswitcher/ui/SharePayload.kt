package com.wallpaperswitcher.ui

import android.net.Uri

/**
 * What a system 分享 intent (ACTION_SEND / ACTION_SEND_MULTIPLE) handed us.
 *
 * Media is copied into the app right away (see SharedMediaImporter); a text
 * payload is treated as a subscription URL / 阅读 share link, which prefills
 * the existing 订阅导入 dialog.
 */
sealed interface SharePayload {
    data class Link(val text: String) : SharePayload
    data class Media(val uris: List<Uri>) : SharePayload
}
