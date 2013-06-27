/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.dialer.list;

import android.app.Activity;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract.Directory;
import android.util.Log;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;

import com.android.contacts.common.list.ContactEntryListAdapter;
import com.android.contacts.common.list.ContactListItemView;
import com.android.contacts.common.list.PhoneNumberPickerFragment;
import com.android.dialer.dialpad.SmartDialCursorLoader;

/**
 * Implements a fragment to load and display SmartDial search results.
 */
public class SmartDialSearchFragment extends PhoneNumberPickerFragment {
    private static final String TAG = SmartDialSearchFragment.class.getSimpleName();

    private OnListFragmentScrolledListener mActivityScrollListener;

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        setQuickContactEnabled(true);
        setDarkTheme(false);
        setPhotoPosition(ContactListItemView.getDefaultPhotoPosition(true /* opposite */));
        setUseCallableUri(true);

        try {
            mActivityScrollListener = (OnListFragmentScrolledListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement OnListFragmentScrolledListener");
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        getListView().setOnScrollListener(new OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                mActivityScrollListener.onListFragmentScrollStateChange(scrollState);
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                    int totalItemCount) {
            }
        });
    }

    /**
     * Creates a SmartDialListAdapter to display and operate on search results.
     */
    @Override
    protected ContactEntryListAdapter createListAdapter() {
        SmartDialNumberListAdapter adapter = new SmartDialNumberListAdapter(getActivity());
        adapter.setUseCallableUri(super.usesCallableUri());
        adapter.setQuickContactEnabled(true);
        return adapter;
    }

    /**
     * Creates a SmartDialCursorLoader object to load query results.
     */
    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        // Smart dialing does not support Directory Load, falls back to normal search instead.
        if (id == getDirectoryLoaderId()) {
            Log.v(TAG, "Directory load");
            return super.onCreateLoader(id, args);
        } else {
            Log.v(TAG, "Creating loader");
            final SmartDialNumberListAdapter adapter = (SmartDialNumberListAdapter) getAdapter();
            SmartDialCursorLoader loader = new SmartDialCursorLoader(super.getContext());
            adapter.configureLoader(loader);
            return loader;
        }
    }

    /**
     * Gets the Phone Uri of an entry for calling.
     * @param position Location of the data of interest.
     * @return Phone Uri to establish a phone call.
     */
    @Override
    protected Uri getPhoneUri(int position) {
        final SmartDialNumberListAdapter adapter = (SmartDialNumberListAdapter) getAdapter();
        return adapter.getDataUri(position);
    }

    @Override
    protected void setSearchMode(boolean flag) {
        super.setSearchMode(flag);
        // This hides the "All contacts with phone numbers" header in the search fragment
        getAdapter().setHasHeader(0, false);
    }
}
