/*
 * Created by Angel Leon (@gubatron), Alden Torres (aldenml)
 * Copyright (c) 2011-2015, FrostWire(R). All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.frostwire.android.gui.fragments;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.frostwire.android.R;
import com.frostwire.android.core.ConfigurationManager;
import com.frostwire.android.core.Constants;
import com.frostwire.android.gui.LocalSearchEngine;
import com.frostwire.android.gui.activities.MainActivity;
import com.frostwire.android.gui.adapters.SearchResultListAdapter;
import com.frostwire.android.gui.adapters.SearchResultListAdapter.FilteredSearchResults;
import com.frostwire.android.gui.dialogs.NewTransferDialog;
import com.frostwire.android.gui.services.Engine;
import com.frostwire.android.gui.tasks.DownloadSoundcloudFromUrlTask;
import com.frostwire.android.gui.tasks.StartDownloadTask;
import com.frostwire.android.gui.transfers.HttpSlideSearchResult;
import com.frostwire.android.gui.transfers.TransferManager;
import com.frostwire.android.gui.util.UIUtils;
import com.frostwire.android.gui.views.*;
import com.frostwire.android.gui.views.AbstractDialog.OnDialogClickListener;
import com.frostwire.android.gui.views.PromotionsView.OnPromotionClickListener;
import com.frostwire.android.gui.views.SearchInputView.OnSearchListener;
import com.frostwire.frostclick.Slide;
import com.frostwire.frostclick.SlideList;
import com.frostwire.frostclick.TorrentPromotionSearchResult;
import com.frostwire.logging.Logger;
import com.frostwire.search.*;
import com.frostwire.search.torrent.TorrentCrawledSearchResult;
import com.frostwire.search.torrent.TorrentSearchResult;
import com.frostwire.util.*;
import com.frostwire.uxstats.UXAction;
import com.frostwire.uxstats.UXStats;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author gubatron
 * @author aldenml
 *
 */
public final class SearchFragment extends AbstractFragment implements MainFragment, OnDialogClickListener, CurrentQueryReporter {
    private static final Logger LOG = Logger.getLogger(SearchFragment.class);

    private SearchResultListAdapter adapter;
    private List<Slide> slides;

    private SearchInputView searchInput;
    private ProgressBar deepSearchProgress;
    private PromotionsView promotions;
    private SearchProgressView searchProgress;
    private ListView list;

    private String currentQuery;

    private final FileTypeCounter fileTypeCounter;

    public SearchFragment() {
        super(R.layout.fragment_search);
        fileTypeCounter = new FileTypeCounter();
        currentQuery = null;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        setupAdapter();

        if (slides != null) {
            promotions.setSlides(slides);
        } else {
            new LoadSlidesTask(this).execute();
        }

        setRetainInstance(true);
    }

    @Override
    public View getHeader(Activity activity) {

        LayoutInflater inflater = LayoutInflater.from(activity);
        @SuppressLint("InflateParams") TextView header = (TextView) inflater.inflate(R.layout.view_main_fragment_simple_header, null);
        header.setText(R.string.search);

        return header;
    }

    @Override
    public void onResume() {
        super.onResume();

        if (adapter != null && (adapter.getCount() > 0 || adapter.getTotalCount() > 0)) {
            refreshFileTypeCounters(true);
        }
    }
    
    @Override
    public void onSaveInstanceState(Bundle outState) {
      //No call for super(). Bug on API Level > 11.
    }

    @Override
    protected void initComponents(final View view) {

        searchInput = findView(view, R.id.fragment_search_input);
        searchInput.setShowKeyboardOnPaste(true);
        searchInput.setOnSearchListener(new OnSearchListener() {
            public void onSearch(View v, String query, int mediaTypeId) {
                if (query.contains("://m.soundcloud.com/") || query.contains("://soundcloud.com/")) {
                    cancelSearch();
                    new DownloadSoundcloudFromUrlTask(getActivity(), query).execute();
                    searchInput.setText("");
                } else if (query.contains("youtube.com/")) {
                    performYTSearch(query);
                } else if (query.startsWith("magnet:?xt=urn:btih:")) {
                    startMagnetDownload(query);
                    currentQuery=null;
                    searchInput.setText("");
                }
                else {
                    performSearch(query, mediaTypeId);
                }
            }

            public void onMediaTypeSelected(View v, int mediaTypeId) {
                adapter.setFileType(mediaTypeId);
                showSearchView(view);
            }

            public void onClear(View v) {
                cancelSearch();
            }
        });

        deepSearchProgress = findView(view, R.id.fragment_search_deepsearch_progress);
        deepSearchProgress.setVisibility(View.GONE);

        promotions = findView(view, R.id.fragment_search_promos);
        promotions.setOnPromotionClickListener(new OnPromotionClickListener() {
            @Override
            public void onPromotionClick(PromotionsView v, Slide slide) {
                startPromotionDownload(slide);
            }
        });


        searchProgress = findView(view, R.id.fragment_search_search_progress);
        searchProgress.setCurrentQueryReporter(this);

        searchProgress.setCancelOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (LocalSearchEngine.instance().isSearchFinished()) {
                    performSearch(searchInput.getText(), adapter.getFileType()); // retry
                } else {
                    cancelSearch();
                }
            }
        });
        
        list = findView(view, R.id.fragment_search_list);

        showSearchView(view);

        if (Constants.IS_GOOGLE_PLAY_DISTRIBUTION) {
            showRatingsReminder(view);
        }
    }

    private void startMagnetDownload(String magnet) {
        //go!
        TransferManager.instance().downloadTorrent(magnet);

        //Show me the transfer tab
        Intent i = new Intent(getActivity(), MainActivity.class);
        i.setAction(Constants.ACTION_SHOW_TRANSFERS);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    private static String extractYTId(String ytUrl) {
        String vId = null;
        Pattern pattern = Pattern.compile(".*(?:youtu.be\\/|v\\/|u\\/\\w\\/|embed\\/|watch\\?v=)([^#\\&\\?]*).*");
        Matcher matcher = pattern.matcher(ytUrl);
        if (matcher.matches()){
            vId = matcher.group(1);
        }
        return vId;
    }

    private void setupAdapter() {
        if (adapter == null) {
            adapter = new SearchResultListAdapter(getActivity()) {
                @Override
                protected void searchResultClicked(SearchResult sr) {
                    startTransfer(sr, getString(R.string.download_added_to_queue));
                }
            };

            LocalSearchEngine.instance().registerListener(new SearchManagerListener() {
                @Override
                public void onResults(SearchPerformer performer, final List<? extends SearchResult> results) {
                    @SuppressWarnings("unchecked")
                    FilteredSearchResults fsr = adapter.filter((List<SearchResult>) results);
                    final List<SearchResult> filteredList = fsr.filtered;

                    fileTypeCounter.add(fsr);

                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            adapter.addResults(results, filteredList);
                            showSearchView(getView());
                            refreshFileTypeCounters(true);
                        }
                    });
                }

                @Override
                public void onFinished(long token) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            searchProgress.setProgressEnabled(false);
                            setupRetrySuggestions();
                            deepSearchProgress.setVisibility(View.GONE);
                        }
                    });
                }
            });
        }
        
        list.setAdapter(adapter);
    }

    private void refreshFileTypeCounters(boolean fileTypeCountersVisible) {
        searchInput.updateFileTypeCounter(Constants.FILE_TYPE_APPLICATIONS, fileTypeCounter.fsr.numApplications);
        searchInput.updateFileTypeCounter(Constants.FILE_TYPE_AUDIO, fileTypeCounter.fsr.numAudio);
        searchInput.updateFileTypeCounter(Constants.FILE_TYPE_DOCUMENTS, fileTypeCounter.fsr.numDocuments);
        searchInput.updateFileTypeCounter(Constants.FILE_TYPE_PICTURES, fileTypeCounter.fsr.numPictures);
        searchInput.updateFileTypeCounter(Constants.FILE_TYPE_TORRENTS, fileTypeCounter.fsr.numTorrents);
        searchInput.updateFileTypeCounter(Constants.FILE_TYPE_VIDEOS, fileTypeCounter.fsr.numVideo);

        searchInput.setFileTypeCountersVisible(fileTypeCountersVisible);
    }

    public void performYTSearch(String query) {
        String ytId=extractYTId(query);
        if (ytId != null){
            searchInput.setText("");
            searchInput.performClickOnRadioButton(Constants.FILE_TYPE_VIDEOS);
            performSearch(ytId,Constants.FILE_TYPE_VIDEOS);
            searchInput.setHint(getActivity().getString(R.string.searching_for) + " youtube:"+ytId);
        }
    }
    
    private void performSearch(String query, int mediaTypeId) {
        adapter.clear();
        adapter.setFileType(mediaTypeId);
        fileTypeCounter.clear();
        refreshFileTypeCounters(false);
        currentQuery = query;
        LocalSearchEngine.instance().performSearch(query);
        searchProgress.setProgressEnabled(true);
        showSearchView(getView());
        UXStats.instance().log(UXAction.SEARCH_STARTED_ENTER_KEY);
    }

    private void cancelSearch() {
        adapter.clear();
        fileTypeCounter.clear();
        refreshFileTypeCounters(false);
        currentQuery = null;
        LocalSearchEngine.instance().cancelSearch();
        searchProgress.setProgressEnabled(false);
        showSearchView(getView());
    }

    private void showSearchView(View view) {
        if (LocalSearchEngine.instance().isSearchStopped()) {
            switchView(view, R.id.fragment_search_promos);
            deepSearchProgress.setVisibility(View.GONE);
        } else {
            if (adapter != null && adapter.getCount() > 0) {
                switchView(view, R.id.fragment_search_list);
                deepSearchProgress.setVisibility(LocalSearchEngine.instance().isSearchFinished() ? View.GONE : View.VISIBLE);
            } else {
                switchView(view, R.id.fragment_search_search_progress);
                deepSearchProgress.setVisibility(View.GONE);
            }
        }

        boolean searchFinished = LocalSearchEngine.instance().isSearchFinished();
        searchProgress.setProgressEnabled(!searchFinished);
        if (searchFinished) {
            setupRetrySuggestions();
        }
    }

    public void setupRetrySuggestions() {
        try {
            searchProgress.setupRetrySuggestions(buildSuggestions(), new SearchProgressView.OnRetryListener() {
                public void onRetry(SearchProgressView v, String keywords) {
                    searchInput.setText(keywords);
                    if (adapter != null) {
                        performSearch(keywords, adapter.getFileType());
                    }
                }
            });
        } catch (Throwable e) {
            LOG.error("Error setting up search suggestions", e);
            searchProgress.hideRetryViews();
        }
    }

    private String[] buildSuggestions() {
        final String searchText = searchInput.getText().trim();
        if (searchText.isEmpty()) {
            return new String[0];
        }
        final String[] split = StringUtils.removeDoubleSpaces(searchText).split(" ");

        if (split.length == 1) {
            return new String[0];
        }

        final String[] suggestions = new String[Math.min(split.length-1,4)];
        for (int i=0; i < suggestions.length ; i++) {
            suggestions[i] = TextUtils.join(" ", Arrays.copyOfRange(split, 0, suggestions.length-i));
        }
        return suggestions;
    }

    private void switchView(View v, int id) {
        if (v != null) {
            FrameLayout frameLayout = findView(v, R.id.fragment_search_framelayout);

            int childCount = frameLayout.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View childAt = frameLayout.getChildAt(i);
                childAt.setVisibility((childAt.getId() == id) ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    @Override
    public void onDialogClick(String tag, int which) {
        if (tag.equals(NewTransferDialog.TAG) && which == AbstractDialog.BUTTON_POSITIVE) {
            if (Ref.alive(NewTransferDialog.srRef)) {
                startDownload(this.getActivity(), NewTransferDialog.srRef.get(), getString(R.string.download_added_to_queue));
            }
        }
    }

    private void startTransfer(final SearchResult sr, final String toastMessage) {
        if (ConfigurationManager.instance().getBoolean(Constants.PREF_KEY_GUI_SHOW_NEW_TRANSFER_DIALOG)) {
            if (sr instanceof FileSearchResult) {
                NewTransferDialog dlg = NewTransferDialog.newInstance((FileSearchResult) sr, false);
                dlg.show(getFragmentManager());
            }
        } else {
            if (isVisible()) {
                startDownload(getActivity(), sr, toastMessage);
            }
        }
        uxLogAction(sr);
    }
    
    private static void startDownload(Context ctx, SearchResult sr, String message) {
        StartDownloadTask task = new StartDownloadTask(ctx, sr, message);
        if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.HONEYCOMB) {
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
            task.execute();
        }
        UIUtils.showTransfersOnDownloadStart(ctx);
    }

    private void showRatingsReminder(View v) {
        final RichNotification ratingReminder = findView(v, R.id.fragment_search_rating_reminder_notification);
        ratingReminder.setVisibility(View.GONE);

        final ConfigurationManager CM = ConfigurationManager.instance();
        boolean alreadyRated = CM.getBoolean(Constants.PREF_KEY_GUI_ALREADY_RATED_US_IN_MARKET);

        if (alreadyRated) {
            return;
        }

        if (ratingReminder.wasDismissed()) {
            return;
        }

        final int finishedDownloads = Engine.instance().getNotifiedDownloadsBloomFilter().count();
        final int REMINDER_INTERVAL = CM.getInt(Constants.PREF_KEY_GUI_FINISHED_DOWNLOADS_BETWEEN_RATINGS_REMINDER);

        if (finishedDownloads > 1 && finishedDownloads % REMINDER_INTERVAL != 0) {
            return;
        }

        ratingReminder.setVisibility(View.VISIBLE);
        ratingReminder.setOnClickListener(new ClickAdapter<SearchFragment>(SearchFragment.this) {
            @Override
            public void onClick(SearchFragment owner, View v) {
                ratingReminder.setVisibility(View.GONE);
                CM.setBoolean(Constants.PREF_KEY_GUI_ALREADY_RATED_US_IN_MARKET, true);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse("market://details?id=" + Constants.APP_PACKAGE_NAME));
                try {
                    startActivity(intent);
                } catch (Throwable t) {
                }
            }
        });
    }

    private void startPromotionDownload(Slide slide) {
        SearchResult sr;

        switch (slide.method) {
        case Slide.DOWNLOAD_METHOD_TORRENT:
            sr = new TorrentPromotionSearchResult(slide);
            break;
        case Slide.DOWNLOAD_METHOD_HTTP:
            sr = new HttpSlideSearchResult(slide);
            break;
        default:
            sr = null;
            break;
        }
        if (sr == null) {

            //check if there is a URL available to open a web browser.
            if (slide.url != null) {
                Intent i = new Intent("android.intent.action.VIEW", Uri.parse(slide.url));
                getActivity().startActivity(i);
            }

            return;
        }

        String stringDownloadingPromo;

        try {
            stringDownloadingPromo = getString(R.string.downloading_promotion, sr.getDisplayName());
        }  catch (Throwable e) {
             stringDownloadingPromo = getString(R.string.azureus_manager_item_downloading);
        }

        startTransfer(sr, stringDownloadingPromo);
    }

    private void uxLogAction(SearchResult sr) {
        UXStats.instance().log(UXAction.SEARCH_RESULT_CLICKED);

        if (sr instanceof HttpSearchResult) {
            UXStats.instance().log(UXAction.DOWNLOAD_CLOUD_FILE);
        } else if (sr instanceof TorrentSearchResult) {
            if (sr instanceof TorrentCrawledSearchResult) {
                UXStats.instance().log(UXAction.DOWNLOAD_PARTIAL_TORRENT_FILE);
            } else {
                UXStats.instance().log(UXAction.DOWNLOAD_FULL_TORRENT_FILE);
            }
        }
    }

    @Override
    public String getCurrentQuery() {
        return currentQuery;
    }

    private static class LoadSlidesTask extends AsyncTask<Void, Void, List<Slide>> {

        private final WeakReference<SearchFragment> fragment;

        public LoadSlidesTask(SearchFragment fragment) {
            this.fragment = new WeakReference<SearchFragment>(fragment);
        }

        @Override
        protected List<Slide> doInBackground(Void... params) {
            try {
                HttpClient http = HttpClientFactory.newInstance();
                String url = String.format("%s?from=android&fw=%s&sdk=%s", Constants.SERVER_PROMOTIONS_URL, Constants.FROSTWIRE_VERSION_STRING, Build.VERSION.SDK_INT);
                String json = http.get(url);
                SlideList slides = JsonUtils.toObject(json, SlideList.class);
                return slides.slides;
            } catch (Throwable e) {
                LOG.error("Error loading slides from url", e);
            }
            return null;
        }

        @Override
        protected void onPostExecute(List<Slide> result) {
            SearchFragment f = fragment.get();
            if (f != null && result != null && !result.isEmpty()) {
                f.slides = result;
                f.promotions.setSlides(result);
            }
        }
    }

    private static final class FileTypeCounter {

        private final FilteredSearchResults fsr = new FilteredSearchResults();

        public void add(FilteredSearchResults fsr) {
            this.fsr.numAudio += fsr.numAudio;
            this.fsr.numApplications += fsr.numApplications;
            this.fsr.numDocuments += fsr.numDocuments;
            this.fsr.numPictures += fsr.numPictures;
            this.fsr.numTorrents += fsr.numTorrents;
            this.fsr.numVideo += fsr.numVideo;
        }

        public void clear() {
            this.fsr.numAudio = 0;
            this.fsr.numApplications = 0;
            this.fsr.numDocuments = 0;
            this.fsr.numPictures = 0;
            this.fsr.numTorrents = 0;
            this.fsr.numVideo = 0;
        }
    }
}
