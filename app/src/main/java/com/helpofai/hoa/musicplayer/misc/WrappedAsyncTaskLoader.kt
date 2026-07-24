/*
 * Copyright (c) 2026 HOA Music Player Pro contributors.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */

package com.helpofai.hoa.musicplayer.misc

import android.content.Context
import androidx.loader.content.AsyncTaskLoader

/**

 *
 * @author Rajib Adhikary
 */
abstract class WrappedAsyncTaskLoader<D>

    (context: Context) : AsyncTaskLoader<D>(context) {

    private var mData: D? = null


    override fun deliverResult(data: D?) {
        if (!isReset) {
            this.mData = data
            super.deliverResult(data)
        }
    }


    override fun onStartLoading() {
        super.onStartLoading()
        if (this.mData != null) {
            deliverResult(this.mData)
        } else if (takeContentChanged() || this.mData == null) {
            forceLoad()
        }
    }


    override fun onStopLoading() {
        super.onStopLoading()
        // Attempt to cancel the current load task if possible
        cancelLoad()
    }


    override fun onReset() {
        super.onReset()
        // Ensure the loader is stopped
        onStopLoading()
        this.mData = null
    }
}
