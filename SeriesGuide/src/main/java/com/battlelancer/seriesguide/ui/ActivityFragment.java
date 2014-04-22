/*
 * Copyright 2014 Uwe Trottmann
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

package com.battlelancer.seriesguide.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.Cursor;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.TextView;
import com.actionbarsherlock.app.SherlockFragment;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.WatchedBox;
import com.battlelancer.seriesguide.adapters.ActivitySlowAdapter;
import com.battlelancer.seriesguide.enums.EpisodeFlags;
import com.battlelancer.seriesguide.provider.SeriesGuideContract.Episodes;
import com.battlelancer.seriesguide.provider.SeriesGuideContract.Shows;
import com.battlelancer.seriesguide.provider.SeriesGuideDatabase.Tables;
import com.battlelancer.seriesguide.settings.ActivitySettings;
import com.battlelancer.seriesguide.settings.DisplaySettings;
import com.battlelancer.seriesguide.ui.dialogs.CheckInDialogFragment;
import com.battlelancer.seriesguide.util.DBUtils;
import com.battlelancer.seriesguide.util.EpisodeTools;
import com.battlelancer.seriesguide.util.FlagTask;
import com.battlelancer.seriesguide.util.Utils;
import com.tonicartos.widget.stickygridheaders.StickyGridHeadersBaseAdapterWrapper;
import com.tonicartos.widget.stickygridheaders.StickyGridHeadersGridView;

public class ActivityFragment extends SherlockFragment implements
        LoaderManager.LoaderCallbacks<Cursor>, OnItemClickListener,
        OnSharedPreferenceChangeListener, ActivitySlowAdapter.CheckInListener {

    private static final String TAG = "Activity";

    private static final int CONTEXT_FLAG_WATCHED_ID = 0;

    private static final int CONTEXT_FLAG_UNWATCHED_ID = 1;

    private static final int CONTEXT_CHECKIN_ID = 2;

    private static final int CONTEXT_COLLECTION_ADD_ID = 3;

    private static final int CONTEXT_COLLECTION_REMOVE_ID = 4;

    private StickyGridHeadersGridView mGridView;

    private ActivitySlowAdapter mAdapter;
    private StickyGridHeadersBaseAdapterWrapper mAdapterWrapper;

    /**
     * Data which has to be passed when creating {@link ActivityFragment}. All Bundle extras are
     * strings, except LOADER_ID and EMPTY_STRING_ID.
     */
    public interface InitBundle {

        String TYPE = "type";

        String ANALYTICS_TAG = "analyticstag";

        String LOADER_ID = "loaderid";

        String EMPTY_STRING_ID = "emptyid";
    }

    public interface ActivityType {

        public String UPCOMING = "upcoming";
        public String RECENT = "recent";
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_activity, container, false);

        TextView emptyView = (TextView) v.findViewById(R.id.emptyViewUpcoming);
        emptyView.setText(getString(getArguments().getInt(InitBundle.EMPTY_STRING_ID)));

        mGridView = (StickyGridHeadersGridView) v.findViewById(R.id.gridViewUpcoming);
        mGridView.setEmptyView(emptyView);
        mGridView.setAreHeadersSticky(true);

        return v;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        // setup adapter
        mAdapter = new ActivitySlowAdapter(getActivity(), null, 0, this);
        mAdapter.setIsShowingHeaders(!ActivitySettings.isInfiniteActivity(getActivity()));

        // setup grid view
        mGridView.setAdapter(mAdapter);
        mAdapterWrapper = (StickyGridHeadersBaseAdapterWrapper) mGridView.getAdapter();
        mGridView.setOnItemClickListener(this);

        PreferenceManager.getDefaultSharedPreferences(getActivity())
                .registerOnSharedPreferenceChangeListener(this);

        registerForContextMenu(mGridView);

        setHasOptionsMenu(true);
    }

    @Override
    public void onStart() {
        super.onStart();

        /**
         * Workaround for loader issues on config change. For some reason the
         * activity holds on to an old cursor. Find out why! See
         * https://github.com/UweTrottmann/SeriesGuide/issues/257.
         */
        onRequery();
    }

    @Override
    public void onDestroy() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        prefs.unregisterOnSharedPreferenceChangeListener(this);

        super.onDestroy();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        // only display the action appropriate for the items current state
        menu.add(0, CONTEXT_CHECKIN_ID, 0, R.string.checkin);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        Cursor episode = (Cursor) mAdapterWrapper.getItem(info.position);
        if (episode == null) {
            return;
        }
        if (EpisodeTools.isWatched(episode.getInt(ActivityQuery.WATCHED))) {
            menu.add(0, CONTEXT_FLAG_UNWATCHED_ID, 1, R.string.unmark_episode);
        } else {
            menu.add(0, CONTEXT_FLAG_WATCHED_ID, 1, R.string.mark_episode);
        }
        if (EpisodeTools.isCollected(episode.getInt(ActivityQuery.COLLECTED))) {
            menu.add(0, CONTEXT_COLLECTION_REMOVE_ID, 2, R.string.action_collection_remove);
        } else {
            menu.add(0, CONTEXT_COLLECTION_ADD_ID, 2, R.string.action_collection_add);
        }
    }

    @Override
    public boolean onContextItemSelected(android.view.MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();

        switch (item.getItemId()) {
            case CONTEXT_COLLECTION_ADD_ID: {
                updateEpisodeCollectionState(info.position, true);
                return true;
            }
            case CONTEXT_COLLECTION_REMOVE_ID: {
                updateEpisodeCollectionState(info.position, false);
                return true;
            }
            case CONTEXT_FLAG_WATCHED_ID: {
                updateEpisodeWatchedState(info.position, true);
                return true;
            }
            case CONTEXT_FLAG_UNWATCHED_ID: {
                updateEpisodeWatchedState(info.position, false);
                return true;
            }
            case CONTEXT_CHECKIN_ID: {
                onCheckinEpisode((int) info.id);
                return true;
            }
        }
        return super.onContextItemSelected(item);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        boolean isLightTheme = SeriesGuidePreferences.THEME == R.style.SeriesGuideThemeLight;
        inflater.inflate(isLightTheme ? R.menu.activity_menu_light : R.menu.activity_menu, menu);

        // set menu items to current values
        menu.findItem(R.id.menu_onlyfavorites)
                .setChecked(ActivitySettings.isOnlyFavorites(getActivity()));
        menu.findItem(R.id.menu_nospecials)
                .setChecked(DisplaySettings.isHidingSpecials(getActivity()));
        menu.findItem(R.id.menu_nowatched)
                .setChecked(DisplaySettings.isNoWatchedEpisodes(getActivity()));
        menu.findItem(R.id.menu_infinite_scrolling).setChecked(
                ActivitySettings.isInfiniteActivity(getActivity()));
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        // If the nav drawer is open, hide action items related to the content view
        boolean isDrawerOpen = ((BaseNavDrawerActivity) getActivity()).isDrawerOpen();

        MenuItem filter = menu.findItem(R.id.menu_action_activity_filter);
        filter.setVisible(!isDrawerOpen);
        boolean isFilterApplied = ActivitySettings.isOnlyFavorites(getActivity()) ||
                DisplaySettings.isHidingSpecials(getActivity()) ||
                DisplaySettings.isNoWatchedEpisodes(getActivity()) ||
                ActivitySettings.isInfiniteActivity(getActivity());
        boolean isLightTheme = SeriesGuidePreferences.THEME == R.style.SeriesGuideThemeLight;
        filter.setIcon(isFilterApplied ?
                (isLightTheme ? R.drawable.ic_action_filter_selected_inverse
                        : R.drawable.ic_action_filter_selected)
                : (isLightTheme ? R.drawable.ic_action_filter_inverse
                        : R.drawable.ic_action_filter));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_onlyfavorites) {
            toggleFilterSetting(item, ActivitySettings.KEY_ONLY_FAVORITE_SHOWS);
            fireTrackerEvent("Only favorite shows Toggle");
            return true;
        } else if (itemId == R.id.menu_nospecials) {
            toggleFilterSetting(item, DisplaySettings.KEY_HIDE_SPECIALS);
            fireTrackerEvent("Hide specials Toggle");
            return true;
        } else if (itemId == R.id.menu_nowatched) {
            toggleFilterSetting(item, DisplaySettings.KEY_NO_WATCHED_EPISODES);
            fireTrackerEvent("Hide watched Toggle");
            return true;
        } else if (itemId == R.id.menu_infinite_scrolling) {
            toggleFilterSetting(item, ActivitySettings.KEY_INFINITE_ACTIVITY);
            fireTrackerEvent("Infinite Scrolling Toggle");
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onCheckinEpisode(int episodeTvdbId) {
        CheckInDialogFragment f = CheckInDialogFragment.newInstance(getActivity(), episodeTvdbId);
        f.show(getFragmentManager(), "checkin-dialog");
    }

    private void updateEpisodeCollectionState(int position, boolean addToCollection) {
        Cursor episode = (Cursor) mAdapterWrapper.getItem(position);
        if (episode == null) {
            return;
        }

        new FlagTask(getActivity(), episode.getInt(ActivityQuery.SHOW_ID))
                .episodeCollected(episode.getInt(ActivityQuery._ID),
                        episode.getInt(ActivityQuery.SEASON),
                        episode.getInt(ActivityQuery.NUMBER),
                        addToCollection)
                .execute();
    }

    private void updateEpisodeWatchedState(int position, boolean isWatched) {
        Cursor episode = (Cursor) mAdapterWrapper.getItem(position);
        if (episode == null) {
            return;
        }

        new FlagTask(getActivity(), episode.getInt(ActivityQuery.SHOW_ID))
                .episodeWatched(episode.getInt(ActivityQuery._ID),
                        episode.getInt(ActivityQuery.SEASON),
                        episode.getInt(ActivityQuery.NUMBER),
                        isWatched ? EpisodeFlags.WATCHED : EpisodeFlags.UNWATCHED)
                .execute();
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        int episodeId = (int) id;

        Intent intent = new Intent();
        intent.setClass(getActivity(), EpisodesActivity.class);
        intent.putExtra(EpisodesActivity.InitBundle.EPISODE_TVDBID, episodeId);

        startActivity(intent);
        getActivity().overridePendingTransition(R.anim.blow_up_enter, R.anim.blow_up_exit);
    }

    public void onRequery() {
        getLoaderManager().restartLoader(getLoaderId(), null, this);
    }

    private int getLoaderId() {
        return getArguments().getInt("loaderid");
    }

    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        String type = getArguments().getString(InitBundle.TYPE);
        boolean isInfiniteScrolling = ActivitySettings.isInfiniteActivity(getActivity());

        // infinite or 30 days activity stream
        String[][] queryArgs = DBUtils.buildActivityQuery(getActivity(), type,
                isInfiniteScrolling ? -1 : 30);

        return new CursorLoader(getActivity(), Episodes.CONTENT_URI_WITHSHOW,
                ActivityQuery.PROJECTION, queryArgs[0][0], queryArgs[1], queryArgs[2][0]);
    }

    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        mAdapter.swapCursor(data);
    }

    public void onLoaderReset(Loader<Cursor> loader) {
        mAdapter.swapCursor(null);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (ActivitySettings.KEY_INFINITE_ACTIVITY.equals(key)) {
            mAdapter.setIsShowingHeaders(!ActivitySettings.isInfiniteActivity(getActivity()));
        }
        if (ActivitySettings.KEY_ONLY_FAVORITE_SHOWS.equals(key)
                || DisplaySettings.KEY_HIDE_SPECIALS.equals(key)
                || DisplaySettings.KEY_NO_WATCHED_EPISODES.equals(key)
                || ActivitySettings.KEY_INFINITE_ACTIVITY.equals(key)) {
            onRequery();
        }
    }

    private void fireTrackerEvent(String label) {
        Utils.trackAction(getActivity(), TAG, label);
    }

    private void toggleFilterSetting(MenuItem item, String key) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        prefs.edit().putBoolean(key, !item.isChecked()).commit();

        // refresh filter icon state
        getActivity().supportInvalidateOptionsMenu();
    }

    public interface ActivityQuery {

        String[] PROJECTION = new String[] {
                Tables.EPISODES + "." + Episodes._ID,
                Episodes.TITLE,
                Episodes.NUMBER,
                Episodes.SEASON,
                Episodes.FIRSTAIREDMS,
                Episodes.WATCHED,
                Episodes.COLLECTED,
                Shows.REF_SHOW_ID,
                Shows.TITLE,
                Shows.NETWORK,
                Shows.POSTER
        };

        String QUERY_UPCOMING = Episodes.FIRSTAIREDMS + ">=? AND " + Episodes.FIRSTAIREDMS
                + "<? AND " + Shows.SELECTION_NO_HIDDEN;

        String QUERY_RECENT = Episodes.FIRSTAIREDMS + "<? AND " + Episodes.FIRSTAIREDMS + ">? AND "
                + Shows.SELECTION_NO_HIDDEN;

        String SORTING_UPCOMING = Episodes.FIRSTAIREDMS + " ASC," + Shows.TITLE + " ASC,"
                + Episodes.NUMBER + " ASC";

        String SORTING_RECENT = Episodes.FIRSTAIREDMS + " DESC," + Shows.TITLE + " ASC,"
                + Episodes.NUMBER + " DESC";

        int _ID = 0;
        int TITLE = 1;
        int NUMBER = 2;
        int SEASON = 3;
        int RELEASE_TIME_MS = 4;
        int WATCHED = 5;
        int COLLECTED = 6;
        int SHOW_ID = 7;
        int SHOW_TITLE = 8;
        int SHOW_NETWORK = 9;
        int SHOW_POSTER = 10;
    }
}
