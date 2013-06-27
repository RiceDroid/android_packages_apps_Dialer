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

package com.android.dialer;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.backup.BackupManager;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Intents.UI;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnFocusChangeListener;
import android.view.ViewConfiguration;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView.OnScrollListener;
import android.widget.PopupMenu;
import android.widget.SearchView;
import android.widget.SearchView.OnCloseListener;
import android.widget.SearchView.OnQueryTextListener;
import android.widget.Toast;

import com.android.contacts.common.CallUtil;
import com.android.contacts.common.activity.TransactionSafeActivity;
import com.android.contacts.common.dialog.ClearFrequentsDialog;
import com.android.contacts.common.interactions.ImportExportDialogFragment;
import com.android.contacts.common.list.ContactListItemView;
import com.android.contacts.common.list.OnPhoneNumberPickerActionListener;
import com.android.contacts.common.list.PhoneNumberPickerFragment;
import com.android.dialer.calllog.NewCallLogActivity;
import com.android.dialer.dialpad.NewDialpadFragment;
import com.android.dialer.dialpad.SmartDialNameMatcher;
import com.android.dialer.interactions.PhoneNumberInteraction;
import com.android.dialer.list.NewPhoneFavoriteFragment;
import com.android.dialer.list.OnListFragmentScrolledListener;
import com.android.dialer.list.SmartDialSearchFragment;
import com.android.internal.telephony.ITelephony;

/**
 * The dialer tab's title is 'phone', a more common name (see strings.xml).
 *
 * TODO krelease: All classes currently prefixed with New will replace the original classes or
 * be renamed more appropriately before shipping.
 */
public class NewDialtactsActivity extends TransactionSafeActivity implements View.OnClickListener,
        NewDialpadFragment.OnDialpadQueryChangedListener, PopupMenu.OnMenuItemClickListener,
        OnListFragmentScrolledListener,
        NewPhoneFavoriteFragment.OnPhoneFavoriteFragmentStartedListener {
    private static final String TAG = "DialtactsActivity";

    public static final boolean DEBUG = false;

    /** Used to open Call Setting */
    private static final String PHONE_PACKAGE = "com.android.phone";
    private static final String CALL_SETTINGS_CLASS_NAME =
            "com.android.phone.CallFeaturesSetting";

    /** @see #getCallOrigin() */
    private static final String CALL_ORIGIN_DIALTACTS =
            "com.android.dialer.DialtactsActivity";

    private static final String TAG_DIALPAD_FRAGMENT = "dialpad";
    private static final String TAG_REGULAR_SEARCH_FRAGMENT = "search";
    private static final String TAG_SMARTDIAL_SEARCH_FRAGMENT = "smartdial";
    private static final String TAG_FAVORITES_FRAGMENT = "favorites";

    /**
     * Just for backward compatibility. Should behave as same as {@link Intent#ACTION_DIAL}.
     */
    private static final String ACTION_TOUCH_DIALER = "com.android.phone.action.TOUCH_DIALER";

    private SharedPreferences mPrefs;

    public static final String SHARED_PREFS_NAME = "com.android.dialer_preferences";

    /** Last manually selected tab index */
    private static final String PREF_LAST_MANUALLY_SELECTED_TAB =
            "DialtactsActivity_last_manually_selected_tab";

    private static final int SUBACTIVITY_ACCOUNT_FILTER = 1;

    private String mFilterText;

    /**
     * The main fragment displaying the user's favorites and frequent contacts
     */
    private NewPhoneFavoriteFragment mPhoneFavoriteFragment;

    /**
     * Fragment containing the dialpad that slides into view
     */
    private NewDialpadFragment mDialpadFragment;

    /**
     * Fragment for searching phone numbers using the alphanumeric keyboard.
     */
    private NewSearchFragment mRegularSearchFragment;

    /**
     * Fragment for searching phone numbers using the dialpad.
     */
    private SmartDialSearchFragment mSmartDialSearchFragment;

    private View mMenuButton;
    private View mCallHistoryButton;
    private View mDialpadButton;

    // Padding view used to shift the fragments up when the dialpad is shown.
    private View mBottomPaddingView;

    /**
     * True when this Activity is in its search UI (with a {@link SearchView} and
     * {@link PhoneNumberPickerFragment}).
     */
    private boolean mInSearchUi;
    private SearchView mSearchView;

    /**
     * The index of the Fragment (or, the tab) that has last been manually selected.
     * This value does not keep track of programmatically set Tabs (e.g. Call Log after a Call)
     */
    private int mLastManuallySelectedFragment;

    /**
     * Listener used when one of phone numbers in search UI is selected. This will initiate a
     * phone call using the phone number.
     */
    private final OnPhoneNumberPickerActionListener mPhoneNumberPickerActionListener =
            new OnPhoneNumberPickerActionListener() {
                @Override
                public void onPickPhoneNumberAction(Uri dataUri) {
                    // Specify call-origin so that users will see the previous tab instead of
                    // CallLog screen (search UI will be automatically exited).
                    PhoneNumberInteraction.startInteractionForPhoneCall(
                        NewDialtactsActivity.this, dataUri, getCallOrigin());
                }

                @Override
                public void onShortcutIntentCreated(Intent intent) {
                    Log.w(TAG, "Unsupported intent has come (" + intent + "). Ignoring.");
                }

                @Override
                public void onHomeInActionBarSelected() {
                    exitSearchUi();
                }
    };

    /**
     * Listener used to send search queries to the phone search fragment.
     */
    private final OnQueryTextListener mPhoneSearchQueryTextListener =
            new OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    View view = getCurrentFocus();
                    if (view != null) {
                        hideInputMethod(view);
                        view.clearFocus();
                    }
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    final boolean smartDialSearch = isDialpadShowing();

                    // Show search result with non-empty text. Show a bare list otherwise.
                    if (TextUtils.isEmpty(newText) && mInSearchUi) {
                        exitSearchUi();
                        return true;
                    } else if (!TextUtils.isEmpty(newText) && !mInSearchUi) {
                        enterSearchUi(smartDialSearch);
                    }

                    if (isDialpadShowing()) {
                        mSmartDialSearchFragment.setQueryString(newText, false);
                    } else {
                        mRegularSearchFragment.setQueryString(newText, false);
                    }
                    return true;
                }
    };

    private boolean isDialpadShowing() {
        return mDialpadFragment.isVisible();
    }

    /**
     * Listener used to handle the "close" button on the right side of {@link SearchView}.
     * If some text is in the search view, this will clean it up. Otherwise this will exit
     * the search UI and let users go back to usual Phone UI.
     *
     * This does _not_ handle back button.
     */
    private final OnCloseListener mPhoneSearchCloseListener =
            new OnCloseListener() {
                @Override
                public boolean onClose() {
                    if (!TextUtils.isEmpty(mSearchView.getQuery())) {
                        mSearchView.setQuery(null, true);
                    }
                    return true;
                }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();
        fixIntent(intent);

        setContentView(R.layout.new_dialtacts_activity);

        getActionBar().hide();

        mPhoneFavoriteFragment = new NewPhoneFavoriteFragment();
        mPhoneFavoriteFragment.setListener(mPhoneFavoriteListener);

        mRegularSearchFragment = new NewSearchFragment();
        mSmartDialSearchFragment = new SmartDialSearchFragment();
        mDialpadFragment = new NewDialpadFragment();

        // TODO krelease: load fragments on demand instead of creating all of them at run time
        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.add(R.id.dialtacts_frame, mPhoneFavoriteFragment, TAG_FAVORITES_FRAGMENT);
        ft.add(R.id.dialtacts_frame, mRegularSearchFragment, TAG_REGULAR_SEARCH_FRAGMENT);
        ft.add(R.id.dialtacts_frame, mSmartDialSearchFragment, TAG_SMARTDIAL_SEARCH_FRAGMENT);
        ft.add(R.id.dialtacts_container, mDialpadFragment, TAG_DIALPAD_FRAGMENT);
        ft.hide(mRegularSearchFragment);
        ft.hide(mDialpadFragment);
        ft.hide(mSmartDialSearchFragment);
        ft.commit();

        mBottomPaddingView = findViewById(R.id.dialtacts_bottom_padding);
        prepareSearchView();

        // Load the last manually loaded tab
        mPrefs = this.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);

        /*
         * TODO krelease : Remember which fragment was last displayed, and then redisplay it as
         * necessary. mLastManuallySelectedFragment = mPrefs.getInt(PREF_LAST_MANUALLY_SELECTED_TAB,
         * PREF_LAST_MANUALLY_SELECTED_TAB_DEFAULT); if (mLastManuallySelectedFragment >=
         * TAB_INDEX_COUNT) { // Stored value may have exceeded the number of current tabs. Reset
         * it. mLastManuallySelectedFragment = PREF_LAST_MANUALLY_SELECTED_TAB_DEFAULT; }
         */

        if (UI.FILTER_CONTACTS_ACTION.equals(intent.getAction())
                && savedInstanceState == null) {
            setupFilterText(intent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        final FragmentManager fm = getFragmentManager();
        mPhoneFavoriteFragment = (NewPhoneFavoriteFragment) fm.findFragmentByTag(
                TAG_FAVORITES_FRAGMENT);
        mDialpadFragment = (NewDialpadFragment) fm.findFragmentByTag(TAG_DIALPAD_FRAGMENT);

        mRegularSearchFragment = (NewSearchFragment) fm.findFragmentByTag(
                TAG_REGULAR_SEARCH_FRAGMENT);
        mRegularSearchFragment.setOnPhoneNumberPickerActionListener(
                mPhoneNumberPickerActionListener);
        if (!mRegularSearchFragment.isHidden()) {
            final FragmentTransaction transaction = getFragmentManager().beginTransaction();
            transaction.hide(mRegularSearchFragment);
            transaction.commit();
        }

        mSmartDialSearchFragment = (SmartDialSearchFragment) fm.findFragmentByTag(
                TAG_SMARTDIAL_SEARCH_FRAGMENT);
        mSmartDialSearchFragment.setOnPhoneNumberPickerActionListener(
                mPhoneNumberPickerActionListener);
        if (!mSmartDialSearchFragment.isHidden()) {
            final FragmentTransaction transaction = getFragmentManager().beginTransaction();
            transaction.hide(mSmartDialSearchFragment);
            transaction.commit();
        }
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_import_export:
                // We hard-code the "contactsAreAvailable" argument because doing it properly would
                // involve querying a {@link ProviderStatusLoader}, which we don't want to do right
                // now in Dialtacts for (potential) performance reasons. Compare with how it is
                // done in {@link PeopleActivity}.
                ImportExportDialogFragment.show(getFragmentManager(), true,
                        DialtactsActivity.class);
                return true;
            case R.id.menu_clear_frequents:
                ClearFrequentsDialog.show(getFragmentManager());
                return true;
            case R.id.add_contact:
                try {
                    startActivity(new Intent(Intent.ACTION_INSERT, Contacts.CONTENT_URI));
                } catch (ActivityNotFoundException e) {
                    Toast toast = Toast.makeText(this, R.string.add_contact_not_available,
                            Toast.LENGTH_SHORT);
                    toast.show();
                }
                return true;
            case R.id.menu_call_settings:
                final Intent settingsIntent = DialtactsActivity.getCallSettingsIntent();
                startActivity(settingsIntent);
        }
        return false;
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.overflow_menu: {
                final PopupMenu popupMenu = new PopupMenu(NewDialtactsActivity.this, view);
                final Menu menu = popupMenu.getMenu();
                popupMenu.inflate(R.menu.dialtacts_options_new);
                popupMenu.setOnMenuItemClickListener(this);
                popupMenu.show();
                break;
            }
            case R.id.dialpad_button:
                showDialpadFragment();
                break;
            case R.id.call_history_button:
                final Intent intent = new Intent(this, NewCallLogActivity.class);
                startActivity(intent);
                break;
            default: {
                Log.wtf(TAG, "Unexpected onClick event from " + view);
                break;
            }
        }
    }

    private void showDialpadFragment() {
        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.setCustomAnimations(R.anim.slide_in, 0);
        ft.show(mDialpadFragment);
        ft.commit();
    }

    private void hideDialpadFragment() {
        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.setCustomAnimations(0, R.anim.slide_out);
        ft.hide(mDialpadFragment);
        ft.commit();
    }

    private void prepareSearchView() {
        mSearchView = (SearchView) findViewById(R.id.search_view);
        mSearchView.setOnQueryTextListener(mPhoneSearchQueryTextListener);
        mSearchView.setOnCloseListener(mPhoneSearchCloseListener);
        // Since we're using a custom layout for showing SearchView instead of letting the
        // search menu icon do that job, we need to manually configure the View so it looks
        // "shown via search menu".
        // - it should be iconified by default
        // - it should not be iconified at this time
        // See also comments for onActionViewExpanded()/onActionViewCollapsed()
        mSearchView.setIconifiedByDefault(true);
        mSearchView.setQueryHint(getString(R.string.dialer_hint_find_contact));
        mSearchView.setIconified(false);
        mSearchView.clearFocus();
        mSearchView.setOnQueryTextFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus) {
                    showInputMethod(view.findFocus());
                }
            }
        });
    }

    private void hideDialpadFragmentIfNecessary() {
        if (mDialpadFragment.isVisible()) {
            hideDialpadFragment();
        }
    }

    final AnimatorListener mHideListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mSearchView.setVisibility(View.GONE);
        }
    };

    public void hideSearchBar() {
        mSearchView.animate().cancel();
        mSearchView.setAlpha(1);
        mSearchView.setTranslationY(0);
        mSearchView.animate().withLayer().alpha(0).translationY(-mSearchView.getHeight()).
                setDuration(200).setListener(mHideListener);

        mPhoneFavoriteFragment.getView().animate().withLayer()
                .translationY(-mSearchView.getHeight()).setDuration(200).setListener(
                    new AnimatorListenerAdapter() {
                    @Override
                        public void onAnimationEnd(Animator animation) {
                            mBottomPaddingView.setVisibility(View.VISIBLE);
                            mPhoneFavoriteFragment.getView().setTranslationY(0);
                        }
                    });
    }

    public void showSearchBar() {
        mSearchView.animate().cancel();
        mSearchView.setAlpha(0);
        mSearchView.setTranslationY(-mSearchView.getHeight());
        mSearchView.animate().withLayer().alpha(1).translationY(0).setDuration(200)
                .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationStart(Animator animation) {
                            mSearchView.setVisibility(View.VISIBLE);
                        }
                });

        mPhoneFavoriteFragment.getView().setTranslationY(-mSearchView.getHeight());
        mPhoneFavoriteFragment.getView().animate().withLayer().translationY(0).setDuration(200)
                .setListener(
                        new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationStart(Animator animation) {
                                    mBottomPaddingView.setVisibility(View.GONE);
                                }
                        });
    }


    public void setupFakeActionBarItems() {
        mMenuButton = findViewById(R.id.overflow_menu);
        if (mMenuButton != null) {
            // mMenuButton.setMinimumWidth(fakeMenuItemWidth);
            if (ViewConfiguration.get(this).hasPermanentMenuKey()) {
                // This is required for dialpad button's layout, so must not use GONE here.
                mMenuButton.setVisibility(View.INVISIBLE);
            } else {
                mMenuButton.setOnClickListener(this);
            }
        }

        mCallHistoryButton = findViewById(R.id.call_history_button);
        // mCallHistoryButton.setMinimumWidth(fakeMenuItemWidth);
        mCallHistoryButton.setOnClickListener(this);

        mDialpadButton = findViewById(R.id.dialpad_button);
        // DialpadButton.setMinimumWidth(fakeMenuItemWidth);
        mDialpadButton.setOnClickListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPrefs.edit().putInt(PREF_LAST_MANUALLY_SELECTED_TAB, mLastManuallySelectedFragment)
                .apply();
        requestBackup();
    }

    private void requestBackup() {
        final BackupManager bm = new BackupManager(this);
        bm.dataChanged();
    }

    private void fixIntent(Intent intent) {
        // This should be cleaned up: the call key used to send an Intent
        // that just said to go to the recent calls list.  It now sends this
        // abstract action, but this class hasn't been rewritten to deal with it.
        if (Intent.ACTION_CALL_BUTTON.equals(intent.getAction())) {
            intent.setDataAndType(Calls.CONTENT_URI, Calls.CONTENT_TYPE);
            intent.putExtra("call_key", true);
            setIntent(intent);
        }
    }

    /**
     * Returns true if the intent is due to hitting the green send key (hardware call button:
     * KEYCODE_CALL) while in a call.
     *
     * @param intent the intent that launched this activity
     * @param recentCallsRequest true if the intent is requesting to view recent calls
     * @return true if the intent is due to hitting the green send key while in a call
     */
    private boolean isSendKeyWhileInCall(Intent intent, boolean recentCallsRequest) {
        // If there is a call in progress go to the call screen
        if (recentCallsRequest) {
            final boolean callKey = intent.getBooleanExtra("call_key", false);

            try {
                ITelephony phone = ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
                if (callKey && phone != null && phone.showCallScreen()) {
                    return true;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to handle send while in call", e);
            }
        }

        return false;
    }

    /**
     * Sets the current tab based on the intent's request type
     *
     * @param intent Intent that contains information about which tab should be selected
     */
    private void displayFragment(Intent intent) {
        // TODO krelease: Make navigation via intent work by displaying the correct fragment
        // as appropriate.

        // If we got here by hitting send and we're in call forward along to the in-call activity
        boolean recentCallsRequest = Calls.CONTENT_TYPE.equals(intent.resolveType(
            getContentResolver()));
        if (isSendKeyWhileInCall(intent, recentCallsRequest)) {
            finish();
            return;
        }
    }

    @Override
    public void onNewIntent(Intent newIntent) {
        setIntent(newIntent);
        fixIntent(newIntent);
        displayFragment(newIntent);
        final String action = newIntent.getAction();
        if (UI.FILTER_CONTACTS_ACTION.equals(action)) {
            setupFilterText(newIntent);
        }
        if (mInSearchUi || (mRegularSearchFragment != null && mRegularSearchFragment.isVisible())) {
            exitSearchUi();
        }

        // TODO krelease: Handle onNewIntent for all other fragments
        /*
         *if (mViewPager.getCurrentItem() == TAB_INDEX_DIALER) { if (mDialpadFragment != null) {
         * mDialpadFragment.setStartedFromNewIntent(true); } else { Log.e(TAG,
         * "DialpadFragment isn't ready yet when the tab is already selected."); } } else if
         * (mViewPager.getCurrentItem() == TAB_INDEX_CALL_LOG) { if (mCallLogFragment != null) {
         * mCallLogFragment.configureScreenFromIntent(newIntent); } else { Log.e(TAG,
         * "CallLogFragment isn't ready yet when the tab is already selected."); } }
         */
        invalidateOptionsMenu();
    }

    /** Returns true if the given intent contains a phone number to populate the dialer with */
    private boolean isDialIntent(Intent intent) {
        final String action = intent.getAction();
        if (Intent.ACTION_DIAL.equals(action) || ACTION_TOUCH_DIALER.equals(action)) {
            return true;
        }
        if (Intent.ACTION_VIEW.equals(action)) {
            final Uri data = intent.getData();
            if (data != null && CallUtil.SCHEME_TEL.equals(data.getScheme())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns an appropriate call origin for this Activity. May return null when no call origin
     * should be used (e.g. when some 3rd party application launched the screen. Call origin is
     * for remembering the tab in which the user made a phone call, so the external app's DIAL
     * request should not be counted.)
     */
    public String getCallOrigin() {
        return !isDialIntent(getIntent()) ? CALL_ORIGIN_DIALTACTS : null;
    }

    /**
     * Retrieves the filter text stored in {@link #setupFilterText(Intent)}.
     * This text originally came from a FILTER_CONTACTS_ACTION intent received
     * by this activity. The stored text will then be cleared after after this
     * method returns.
     *
     * @return The stored filter text
     */
    public String getAndClearFilterText() {
        String filterText = mFilterText;
        mFilterText = null;
        return filterText;
    }

    /**
     * Stores the filter text associated with a FILTER_CONTACTS_ACTION intent.
     * This is so child activities can check if they are supposed to display a filter.
     *
     * @param intent The intent received in {@link #onNewIntent(Intent)}
     */
    private void setupFilterText(Intent intent) {
        // If the intent was relaunched from history, don't apply the filter text.
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            return;
        }
        String filter = intent.getStringExtra(UI.FILTER_TEXT_EXTRA_KEY);
        if (filter != null && filter.length() > 0) {
            mFilterText = filter;
        }
    }

    private final NewPhoneFavoriteFragment.Listener mPhoneFavoriteListener =
            new NewPhoneFavoriteFragment.Listener() {
        @Override
        public void onContactSelected(Uri contactUri) {
            PhoneNumberInteraction.startInteractionForPhoneCall(
                        NewDialtactsActivity.this, contactUri, getCallOrigin());
        }

        @Override
        public void onCallNumberDirectly(String phoneNumber) {
            Intent intent = CallUtil.getCallIntent(phoneNumber, getCallOrigin());
            startActivity(intent);
        }
    };

    /* TODO krelease: This is only relevant for phones that have a hard button search key (i.e.
     * Nexus S). Supporting it is a little more tricky because of the dialpad fragment might
     * be showing when the search key is pressed so there is more state management involved.

    @Override
    public void startSearch(String initialQuery, boolean selectInitialQuery,
            Bundle appSearchData, boolean globalSearch) {
        if (mRegularSearchFragment != null && mRegularSearchFragment.isAdded() && !globalSearch) {
            if (mInSearchUi) {
                if (mSearchView.hasFocus()) {
                    showInputMethod(mSearchView.findFocus());
                } else {
                    mSearchView.requestFocus();
                }
            } else {
                enterSearchUi();
            }
        } else {
            super.startSearch(initialQuery, selectInitialQuery, appSearchData, globalSearch);
        }
    }*/

    private void showInputMethod(View view) {
        final InputMethodManager imm = (InputMethodManager) getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(view, 0);
        }
    }

    private void hideInputMethod(View view) {
        final InputMethodManager imm = (InputMethodManager) getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (imm != null && view != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    /**
     * Shows the search fragment
     */
    private void enterSearchUi(boolean smartDialSearch) {
        final FragmentTransaction transaction = getFragmentManager().beginTransaction();
        transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        transaction.hide(mPhoneFavoriteFragment);
        if (smartDialSearch) {
            transaction.show(mSmartDialSearchFragment);
        } else {
            transaction.show(mRegularSearchFragment);
        }
        transaction.commit();

        mInSearchUi = true;
    }

    /**
     * Hides the search fragment
     */
    private void exitSearchUi() {
        final FragmentTransaction transaction = getFragmentManager().beginTransaction();
        transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        transaction.hide(mRegularSearchFragment);
        transaction.hide(mSmartDialSearchFragment);
        transaction.show(mPhoneFavoriteFragment);
        transaction.commit();
        mInSearchUi = false;
    }

    /** Returns an Intent to launch Call Settings screen */
    public static Intent getCallSettingsIntent() {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName(PHONE_PACKAGE, CALL_SETTINGS_CLASS_NAME);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    @Override
    public void onBackPressed() {
        if (mDialpadFragment.isVisible()) {
            hideDialpadFragment();
        } else if (mInSearchUi) {
            mSearchView.setQuery(null, false);
        } else if (isTaskRoot()) {
            // Instead of stopping, simply push this to the back of the stack.
            // This is only done when running at the top of the stack;
            // otherwise, we have been launched by someone else so need to
            // allow the user to go back to the caller.
            moveTaskToBack(false);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onDialpadQueryChanged(String query) {
        final String normalizedQuery = SmartDialNameMatcher.normalizeNumber(query,
                SmartDialNameMatcher.LATIN_SMART_DIAL_MAP);
        if (!TextUtils.equals(mSearchView.getQuery(), normalizedQuery)) {
            mSearchView.setQuery(normalizedQuery, false);
        }
    }

    @Override
    public void onListFragmentScrollStateChange(int scrollState) {
        if (scrollState == OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
            hideDialpadFragmentIfNecessary();
            hideInputMethod(getCurrentFocus());
        }
    }

    @Override
    public void onPhoneFavoriteFragmentStarted() {
        setupFakeActionBarItems();
    }
}
