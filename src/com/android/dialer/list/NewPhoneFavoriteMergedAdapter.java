/*
 * Copyright (C) 2011 Google Inc.
 * Licensed to The Android Open Source Project.
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

import android.content.Context;
import android.content.res.Resources;
import android.database.DataSetObserver;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.SectionIndexer;

import com.android.contacts.common.list.ContactEntryListAdapter;
import com.android.contacts.common.list.ContactListItemView;
import com.android.dialer.R;
import com.android.dialer.calllog.NewCallLogAdapter;

/**
 * An adapter that combines items from {@link com.android.contacts.common.list.ContactTileAdapter} and
 * {@link com.android.contacts.common.list.ContactEntryListAdapter} into a single list.
 * In between those two results, an account filter header will be inserted.
 *
 * Has one extra view at the top: The most recent call/voicemail/missed call.
 */
public class NewPhoneFavoriteMergedAdapter extends BaseAdapter implements SectionIndexer {

    private class CustomDataSetObserver extends DataSetObserver {
        @Override
        public void onChanged() {
            notifyDataSetChanged();
        }
    }

    private static final String TAG = NewPhoneFavoriteMergedAdapter.class.getSimpleName();

    private final PhoneFavoritesTileAdapter mContactTileAdapter;
    private final ContactEntryListAdapter mContactEntryListAdapter;
    private final NewCallLogAdapter mCallLogAdapter;
    private final View mAccountFilterHeaderContainer;
    private final View mLoadingView;

    // TODO krelease: Add a setting to toggle mShowAllContacts, and really handle it
    // properly below.
    private boolean mShowAllContacts = false;

    private final int mItemPaddingLeft;
    private final int mItemPaddingRight;

    private final DataSetObserver mObserver;

    public NewPhoneFavoriteMergedAdapter(Context context,
            PhoneFavoritesTileAdapter contactTileAdapter,
            View accountFilterHeaderContainer,
            ContactEntryListAdapter contactEntryListAdapter,
            NewCallLogAdapter callLogAdapter,
            View loadingView) {
        final Resources resources = context.getResources();
        mItemPaddingLeft = resources.getDimensionPixelSize(R.dimen.detail_item_side_margin);
        mItemPaddingRight = resources.getDimensionPixelSize(R.dimen.list_visible_scrollbar_padding);
        mContactTileAdapter = contactTileAdapter;
        mContactEntryListAdapter = contactEntryListAdapter;
        mCallLogAdapter = callLogAdapter;

        mAccountFilterHeaderContainer = accountFilterHeaderContainer;

        mObserver = new CustomDataSetObserver();
        mContactTileAdapter.registerDataSetObserver(mObserver);
        mContactEntryListAdapter.registerDataSetObserver(mObserver);
        mLoadingView = loadingView;
    }

    @Override
    public boolean isEmpty() {
        // Cannot use the super's method here because we add extra rows in getCount() to account
        // for headers
        return mCallLogAdapter.getCount() + mContactTileAdapter.getCount() +
                mContactEntryListAdapter.getCount() == 0;
    }

    @Override
    public int getCount() {
        final int contactTileAdapterCount = mContactTileAdapter.getCount();
        final int contactEntryListAdapterCount = mShowAllContacts ?
                mContactEntryListAdapter.getCount() : 0;

        final int callLogAdapterCount = mCallLogAdapter.getCount();

        if (mShowAllContacts && mContactEntryListAdapter.isLoading()) {
            // Hide "all" contacts during its being loaded. Instead show "loading" view.
            //
            // "+2" for mAccountFilterHeaderContainer and mLoadingView
            return contactTileAdapterCount + 1 + (mShowAllContacts ? 1 : 0) +
                    callLogAdapterCount;
        } else {
            // "+1" for mAccountFilterHeaderContainer
            return contactTileAdapterCount + contactEntryListAdapterCount +
                    (mShowAllContacts ? 1 : 0) + callLogAdapterCount;
        }
    }

    @Override
    public Object getItem(int position) {
        final int contactTileAdapterCount = mContactTileAdapter.getCount();
        final int contactEntryListAdapterCount = mShowAllContacts ?
                mContactEntryListAdapter.getCount() : 0;

        final int callLogAdapterCount = mCallLogAdapter.getCount();

        // TODO krelease: Calculate the position properly.
        if (callLogAdapterCount > 0) {
            if (position < callLogAdapterCount) {
                return mCallLogAdapter.getItem(position);
            }
            position -= callLogAdapterCount;
        }

        if (position < contactTileAdapterCount) {  // For "tile" and "frequent" sections
            return mContactTileAdapter.getItem(position);
        } else {  // For "all" section
            if (mShowAllContacts && mContactEntryListAdapter.isLoading()) {
                // "All" section is being loaded.
                return mLoadingView;
            } else {
                // "-1" for mAccountFilterHeaderContainer
                final int localPosition = position - contactTileAdapterCount - 1;
                return mContactTileAdapter.getItem(localPosition);
            }
        }
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        // "+1" for mLoadingView
        return (mContactTileAdapter.getViewTypeCount()
                + mContactEntryListAdapter.getViewTypeCount()
                + 1
                + mCallLogAdapter.getViewTypeCount());
    }

    @Override
    public int getItemViewType(int position) {
        final int contactTileAdapterCount = mContactTileAdapter.getCount();

        final int contactEntryListAdapterCount = mShowAllContacts ?
                mContactEntryListAdapter.getCount() : 0;

        final int callLogAdapterCount = mCallLogAdapter.getCount();

        if (callLogAdapterCount > 0) {
            if (position == 0) {
                return mContactTileAdapter.getViewTypeCount() +
                        mContactEntryListAdapter.getViewTypeCount() + 2;
            }
            // Ignore the first position when calculating view types of all other items
            position -= callLogAdapterCount;
        }

        // There should be four kinds of types that are usually used, and one more exceptional
        // type (IGNORE_ITEM_VIEW_TYPE), which sometimes comes from mContactTileAdapter.
        //
        // The four ordinary view types have the index equal to or more than 0, and less than
        // mContactTileAdapter.getViewTypeCount()+ mContactEntryListAdapter.getViewTypeCount() + 2.
        // (See also this class's getViewTypeCount())
        //
        // We have those values for:
        // - The view types mContactTileAdapter originally has
        // - The view types mContactEntryListAdapter originally has
        // - mAccountFilterHeaderContainer ("all" section's account header), and
        // - mLoadingView
        //
        // Those types should not be mixed, so we have a different range for each kinds of types:
        // - Types for mContactTileAdapter ("tile" and "frequent" sections)
        //   They should have the index, >=0 and <mContactTileAdapter.getViewTypeCount()
        //
        // - Types for mContactEntryListAdapter ("all" sections)
        //   They should have the index, >=mContactTileAdapter.getViewTypeCount() and
        //   <(mContactTileAdapter.getViewTypeCount() + mContactEntryListAdapter.getViewTypeCount())
        //
        // - Type for "all" section's account header
        //   It should have the exact index
        //   mContactTileAdapter.getViewTypeCount()+ mContactEntryListAdapter.getViewTypeCount()
        //
        // - Type for "loading" view used during "all" section is being loaded.
        //   It should have the exact index
        //   mContactTileAdapter.getViewTypeCount()+ mContactEntryListAdapter.getViewTypeCount() + 1
        //
        // As an exception, IGNORE_ITEM_VIEW_TYPE (-1) will be remained as is, which will be used
        // by framework's Adapter implementation and thus should be left as is.
        if (position < contactTileAdapterCount) {  // For "tile" and "frequent" sections
            return mContactTileAdapter.getItemViewType(position);
        } else if (position == contactTileAdapterCount) {  // For "all" section's account header
            return mContactTileAdapter.getViewTypeCount()
                    + mContactEntryListAdapter.getViewTypeCount();
        } else {  // For "all" section
            if (mContactEntryListAdapter.isLoading()) {  // "All" section is being loaded.
                return mContactTileAdapter.getViewTypeCount()
                        + mContactEntryListAdapter.getViewTypeCount() + 1;
            } else {
                // "-1" for mAccountFilterHeaderContainer
                final int localPosition = position - contactTileAdapterCount - 1;
                final int type = mContactEntryListAdapter.getItemViewType(localPosition);
                // IGNORE_ITEM_VIEW_TYPE must be handled differently.
                return (type < 0) ? type : type + mContactTileAdapter.getViewTypeCount();
            }
        }
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final int contactTileAdapterCount = mContactTileAdapter.getCount();

        final int contactEntryListAdapterCount = mShowAllContacts ?
                mContactEntryListAdapter.getCount() : 0;

        final int callLogAdapterCount = mCallLogAdapter.getCount();

        // TODO krelease: Handle the new callLogAdapterCount and position offsets properly
        if (callLogAdapterCount > 0) {
            if (position == 0) {
                final View view = mCallLogAdapter.getView(position, convertView, parent);
                return view;
            }
            position -= callLogAdapterCount;
        }

        // Obtain a View relevant for that position, and adjust its horizontal padding. Each
        // View has different implementation, so we use different way to control those padding.
        if (position < contactTileAdapterCount) {  // For "tile" and "frequent" sections
            final View view = mContactTileAdapter.getView(position, convertView, parent);
            final int frequentHeaderPosition = mContactTileAdapter.getFrequentHeaderPosition();
            // TODO krelease: Get rid of frequent header position, we don't need it anymore
            if (position >= frequentHeaderPosition) {
                // Views for "frequent" contacts use FrameLayout's margins instead of padding.
                final FrameLayout frameLayout = (FrameLayout) view;
                final View child = frameLayout.getChildAt(0);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT);
                params.setMargins(mItemPaddingLeft, 0, mItemPaddingRight, 0);
                child.setLayoutParams(params);
            }
            return view;
        } else {  // For "all" section
            if (mShowAllContacts && mContactEntryListAdapter.isLoading()) {
                // "All" section is being loaded.
                mLoadingView.setPadding(mItemPaddingLeft,
                        mLoadingView.getPaddingTop(),
                        mItemPaddingRight,
                        mLoadingView.getPaddingBottom());
                return mLoadingView;
            } else {
                final int localPosition = position - contactTileAdapterCount;
                final ContactListItemView itemView = (ContactListItemView)
                        mContactEntryListAdapter.getView(localPosition, convertView, null);
                itemView.setPadding(mItemPaddingLeft, itemView.getPaddingTop(),
                        mItemPaddingRight, itemView.getPaddingBottom());
                itemView.setSelectionBoundsHorizontalMargin(mItemPaddingLeft, mItemPaddingRight);
                return itemView;
            }
        }
    }

    @Override
    public boolean areAllItemsEnabled() {
        // If "all" section is being loaded we'll show mLoadingView, which is not enabled.
        // Otherwise check the all the other components in the ListView and return appropriate
        // result.
        return !mContactEntryListAdapter.isLoading()
                && (mContactTileAdapter.areAllItemsEnabled()
                && mAccountFilterHeaderContainer.isEnabled()
                && mContactEntryListAdapter.areAllItemsEnabled());
    }

    @Override
    public boolean isEnabled(int position) {
        final int contactTileAdapterCount = mContactTileAdapter.getCount();
        final int contactEntryListAdapterCount = mContactEntryListAdapter.getCount();
        if (position < contactTileAdapterCount) {  // For "tile" and "frequent" sections
            return mContactTileAdapter.isEnabled(position);
        } else if (position == contactTileAdapterCount) {  // For "all" section's account header
            // This will be handled by View's onClick event instead of ListView's onItemClick event.
            return false;
        } else {  // For "all" section
            if (mContactEntryListAdapter.isLoading()) {  // "All" section is being loaded.
                return false;
            } else {
                // "-1" for mAccountFilterHeaderContainer
                final int localPosition = position - contactTileAdapterCount - 1;
                return mContactEntryListAdapter.isEnabled(localPosition);
            }
        }
    }

    @Override
    public int getPositionForSection(int sectionIndex) {
        // frequent header view.
        final int contactTileAdapterCount = mContactTileAdapter.getCount();
        final int localPosition = mContactEntryListAdapter.getPositionForSection(sectionIndex);
        return contactTileAdapterCount + localPosition;
    }

    @Override
    public int getSectionForPosition(int position) {
        final int contactTileAdapterCount = mContactTileAdapter.getCount();
        if (position <= contactTileAdapterCount) {
            return 0;
        } else {
            final int localPosition = position - contactTileAdapterCount;
            return mContactEntryListAdapter.getSectionForPosition(localPosition);
        }
    }

    @Override
    public Object[] getSections() {
        return mContactEntryListAdapter.getSections();
    }

    public boolean shouldShowFirstScroller(int firstVisibleItem) {
        final int contactTileAdapterCount = mContactTileAdapter.getCount();
        return firstVisibleItem > contactTileAdapterCount;
    }
}
