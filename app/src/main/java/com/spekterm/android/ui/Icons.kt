/*
 * The icons the app draws (`ui-polish` D3). Path data is from Google's Material Icons
 * (https://github.com/google/material-design-icons), licensed under the Apache License, Version 2.0.
 */
package com.spekterm.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** A 24 dp icon of one filled path; tinted by the `Icon` that draws it. */
private fun icon(name: String, path: String, mirrored: Boolean = false): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirrored)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

object SpekIcons {
    val Back = icon("Back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z", mirrored = true)
    val More = icon(
        "More",
        "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 " +
            "-0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z",
    )
    val Add = icon("Add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val Send = icon("Send", "M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z", mirrored = true)
    val Attach = icon(
        "Attach",
        "M16.5,6v11.5c0,2.21 -1.79,4 -4,4s-4,-1.79 -4,-4V5c0,-1.38 1.12,-2.5 2.5,-2.5s2.5,1.12 2.5,2.5v10.5c0,0.55 " +
            "-0.45,1 -1,1s-1,-0.45 -1,-1V6H10v9.5c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5V5c0,-2.21 -1.79,-4 -4,-4S7," +
            "2.79 7,5v12.5c0,3.04 2.46,5.5 5.5,5.5s5.5,-2.46 5.5,-5.5V6h-1.5z",
    )
    val Close = icon(
        "Close",
        "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
    )
    val ChevronRight = icon("ChevronRight", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z", mirrored = true)
    val ExpandMore = icon("ExpandMore", "M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z")
    val ArrowDown = icon("ArrowDown", "M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.58,-5.59L4,12l8,8 8,-8z")
    val Paste = icon(
        "Paste",
        "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2H5c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h14c1.1,0 " +
            "2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20H5V4h2v3h10V4h2v16z",
    )
    val Succeeded = icon(
        "Succeeded",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z",
    )
    val Error = icon(
        "Error",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-2h2v2zM13,13h-2V7h2v6z",
    )
    val Warning = icon("Warning", "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z")
    val Info = icon(
        "Info",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2V7h2v2z",
    )
    val Help = icon(
        "Help",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,19h-2v-2h2v2zM15.07,11.25l-0.9,0.92C13.45," +
            "12.9 13,13.5 13,15h-2v-0.5c0,-1.1 0.45,-2.1 1.17,-2.83l1.24,-1.26c0.37,-0.36 0.59,-0.86 0.59,-1.41 0,-1.1 " +
            "-0.9,-2 -2,-2s-2,0.9 -2,2H8c0,-2.21 1.79,-4 4,-4s4,1.79 4,4c0,0.88 -0.36,1.68 -0.93,2.25z",
    )
    val Plan = icon(
        "Plan",
        "M3,13h2v-2H3v2zM3,17h2v-2H3v2zM3,9h2V7H3v2zM7,13h14v-2H7v2zM7,17h14v-2H7v2zM7,7v2h14V7H7z",
    )
    val Shell = icon(
        "Shell",
        "M20,4H4C2.89,4 2,4.9 2,6v12c0,1.1 0.89,2 2,2h16c1.1,0 2,-0.9 2,-2V6C22,4.9 21.11,4 20,4zM20,18H4V8h16V18zM18," +
            "17h-6v-2h6V17zM7.5,17l-1.41,-1.41L8.67,13l-2.59,-2.59L7.5,9l4,4L7.5,17z",
    )
    val Agent = icon(
        "Agent",
        "M20,9V7c0,-1.1 -0.9,-2 -2,-2h-3c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5H6C4.9,5 4,5.9 4,7v2c-1.66,0 -3,1.34 -3,3s1.34," +
            "3 3,3v4c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2v-4c1.66,0 3,-1.34 3,-3S21.66,9 20,9zM7.5,11.5C7.5,10.67 8.17,10 " +
            "9,10s1.5,0.67 1.5,1.5S9.83,13 9,13S7.5,12.33 7.5,11.5zM16,17H8v-2h8V17zM15,13c-0.83,0 -1.5,-0.67 -1.5,-1.5S14.17," +
            "10 15,10s1.5,0.67 1.5,1.5S15.83,13 15,13z",
    )
    val Folder = icon(
        "Folder",
        "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z",
    )
    val Dormant = icon(
        "Dormant",
        "M12.34,2.02C6.59,1.82 2,6.42 2,12c0,5.52 4.48,10 10,10c3.71,0 6.93,-2.02 8.66,-5.02C13.15,16.73 8.57,8.55 12.34,2.02z",
    )
    val Image = icon(
        "Image",
        "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5," +
            "12l4.5,6H5l3.5,-4.5z",
    )
    val File = icon(
        "File",
        "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2zM16,14H8v-2h8v2z" +
            "M13,9V3.5L18.5,9H13z",
    )
    val QrCode = icon(
        "QrCode",
        "M3,11h8V3H3V11zM5,5h4v4H5V5zM3,21h8v-8H3V21zM5,15h4v4H5V15zM13,3v8h8V3H13zM19,9h-4V5h4V9zM19,19h2v2h-2zM13,13h2v2" +
            "h-2zM15,15h2v2h-2zM13,17h2v2h-2zM15,19h2v2h-2zM17,17h2v2h-2zM17,13h2v2h-2zM19,15h2v2h-2z",
    )
}
