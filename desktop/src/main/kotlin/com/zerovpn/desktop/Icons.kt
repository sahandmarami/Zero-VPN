package com.zerovpn.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Vector icons ported 1:1 from the Android app's drawable XMLs, so the
 * Windows window shows the exact same glyphs as the phone build.
 */
private fun vec(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        paths.forEach { p ->
            addPath(
                pathData = PathParser().parsePathString(p).toNodes(),
                fill = SolidColor(Color.White),
            )
        }
    }.build()

object ZeroIcons {
    /** ic_menu_24dp */
    val menu: ImageVector by lazy {
        vec("ZeroMenu", "M3,18h18v-2H3v2zM3,13h18v-2H3v2zM3,6v2h18V6H3z")
    }

    /** ic_zero_power_24dp */
    val power: ImageVector by lazy {
        vec(
            "ZeroPower",
            "M13,3H11V13H13V3M17.83,5.17L16.41,6.59C17.99,7.86 19,9.81 19,12C19,15.87 " +
                "15.87,19 12,19C8.13,19 5,15.87 5,12C5,9.81 6,7.86 7.58,6.58L6.17,5.17C4.23,6.82 " +
                "3,9.26 3,12C3,16.97 7.03,21 12,21C16.97,21 21,16.97 21,12C21,9.26 19.77,6.82 17.83,5.17Z"
        )
    }

    /** ic_flash_on_24dp */
    val flash: ImageVector by lazy {
        vec("ZeroFlash", "M7,2v11h3v9l7,-12h-4l4,-8z")
    }

    /** ic_zero_chevron_right_24dp */
    val chevron: ImageVector by lazy {
        vec(
            "ZeroChevron",
            "M9.29,6.71C8.9,7.1 8.9,7.73 9.29,8.12L13.17,12L9.29,15.88C8.9,16.27 8.9,16.9 " +
                "9.29,17.29C9.68,17.68 10.31,17.68 10.7,17.29L15.29,12.7C15.68,12.31 15.68,11.68 " +
                "15.29,11.29L10.7,6.7C10.32,6.32 9.68,6.32 9.29,6.71Z"
        )
    }

    /** ic_zero_home_24dp */
    val home: ImageVector by lazy {
        vec(
            "ZeroHome",
            "M12,3.09L4.24,9.5C4.09,9.63 4,9.81 4,10V19C4,19.28 4.11,19.53 4.29,19.71C4.47,19.89 " +
                "4.72,20 5,20H9V15C9,14.45 9.45,14 10,14H14C14.55,14 15,14.45 15,15V20H19C19.28,20 " +
                "19.53,19.89 19.71,19.71C19.89,19.53 20,19.28 20,19V10C20,9.81 19.91,9.63 " +
                "19.76,9.5L12,3.09M12,0.5L20.46,7.56C20.8,7.85 21,8.27 21,8.72V19C21,19.83 20.66,20.58 " +
                "20.12,21.12C19.58,21.66 18.83,22 18,22H14C13.45,22 13,21.55 13,21V16H11V21C11,21.55 " +
                "10.55,22 10,22H6C5.17,22 4.42,21.66 3.88,21.12C3.34,20.58 3,19.83 3,19V8.72C3,8.27 " +
                "3.2,7.85 3.54,7.56L12,0.5Z"
        )
    }

    /** ic_zero_locations_24dp */
    val locations: ImageVector by lazy {
        vec(
            "ZeroLocations",
            "M12,2C6.48,2 2,6.48 2,12C2,17.52 6.48,22 12,22C17.52,22 22,17.52 22,12C22,6.48 " +
                "17.52,2 12,2M11,19.93C7.05,19.44 4,16.08 4,12C4,11.38 4.08,10.79 4.21,10.21L9,15V16C9,17.1 " +
                "9.9,18 11,18M17.9,17.39C17.64,16.58 16.9,16 16,16H15V13C15,12.45 14.55,12 14,12H8V10H10C10.55,10 " +
                "11,9.55 11,9V7H13C14.1,7 15,6.1 15,5V4.59C17.93,5.78 20,8.65 20,12C20,14.08 19.2,15.99 17.9,17.39Z"
        )
    }

    /** settings gear (material) */
    val settings: ImageVector by lazy {
        vec(
            "ZeroSettings",
            "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 " +
                "-0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 " +
                "-0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 " +
                "-0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 " +
                "7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 " +
                "2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 " +
                "-0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 " +
                "1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 " +
                "0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 " +
                "0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 " +
                "-3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z"
        )
    }

    /** ic_add_24dp */
    val add: ImageVector by lazy {
        vec("ZeroAdd", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    }

    /** ic_search_24dp */
    val search: ImageVector by lazy {
        vec(
            "ZeroSearch",
            "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 " +
                "3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5z" +
                "M9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"
        )
    }

    /** ic_delete_24dp */
    val delete: ImageVector by lazy {
        vec(
            "ZeroDelete",
            "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2L18,7L6,7v12zM8.46,11.88l1.41,-1.41L12,12.59l2.12,-2.12 " +
                "1.41,1.41L13.41,14l2.12,2.12 -1.41,1.41L12,15.41l-2.12,2.12 -1.41,-1.41L10.59,14l-2.13,-2.12z" +
                "M15.5,4l-1,-1h-5l-1,1L5,4v2h14L19,4z"
        )
    }

    /** content copy */
    val copy: ImageVector by lazy {
        vec(
            "ZeroCopy",
            "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 " +
                "2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z"
        )
    }

    /** refresh */
    val refresh: ImageVector by lazy {
        vec(
            "ZeroRefresh",
            "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 " +
                "6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 " +
                "6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z"
        )
    }

    /** close */
    val close: ImageVector by lazy {
        vec(
            "ZeroClose",
            "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 " +
                "19,17.59 13.41,12z"
        )
    }
}
