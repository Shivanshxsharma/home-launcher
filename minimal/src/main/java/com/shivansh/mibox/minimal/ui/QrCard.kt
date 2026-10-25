package com.shivansh.mibox.minimal.ui

import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.shivansh.mibox.minimal.R

/** The QR code on white at the right of Favs, for setting up TMDB from a phone, with a label under it. */
class QrCard(private val root: View) {
    private val code: ImageView = root.findViewById(R.id.qr_code)
    private val label: TextView = root.findViewById(R.id.qr_label)

    /** Null [bitmap] hides the card, e.g. while the code is being made. */
    fun show(bitmap: Bitmap?, text: CharSequence?) {
        code.setImageBitmap(bitmap)
        label.text = text
        root.visibility = if (bitmap == null) View.INVISIBLE else View.VISIBLE
    }
}
