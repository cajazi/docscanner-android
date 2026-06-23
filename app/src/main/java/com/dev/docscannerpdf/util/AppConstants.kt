package com.dev.docscannerpdf.util

object AppConstants {
    const val TAG = "MainActivity"
    const val PDF_MIME_TYPE = "application/pdf"
    const val TEXT_MIME_TYPE = "text/plain"
    const val DOC_MIME_TYPE = "application/msword"
    const val PDF_ONLY_ACTION_MESSAGE = "Available for PDF documents only."
    const val DEFAULT_SCAN_TITLE_PREFIX = "Scan"
    const val ID_CARD_SCAN_TITLE_PREFIX = "ID Card Scan"
    const val APP_LOCK_TIMEOUT_MS = 5L * 60L * 1000L
    const val ROOM_MIGRATION_STATUS = "Registered 1->2->3->4->5->6"
    const val A4_WIDTH_POINTS = 595
    const val A4_HEIGHT_POINTS = 842
    const val MAX_PDF_IMAGE_DIMENSION = 1800
    const val SPLIT_THUMBNAIL_MAX_DIMENSION = 360
    const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    val DANGEROUS_PERMISSION_NAMES = setOf(
        "android.permission.CAMERA",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
        "android.permission.READ_SMS",
        "android.permission.SEND_SMS",
        "android.permission.CALL_PHONE"
    )
}
