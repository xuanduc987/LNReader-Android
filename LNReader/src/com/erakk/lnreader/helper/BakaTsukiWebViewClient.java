package com.erakk.lnreader.helper;

import java.lang.ref.WeakReference;

import android.content.Intent;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.erakk.lnreader.Constants;
import com.erakk.lnreader.LNReaderApplication;
import com.erakk.lnreader.R;
import com.erakk.lnreader.UIHelper;
import com.erakk.lnreader.activity.DisplayImageActivity;
import com.erakk.lnreader.activity.DisplayLightNovelContentActivity;
import com.erakk.lnreader.dao.NovelsDao;
import com.erakk.lnreader.model.PageModel;

public class BakaTsukiWebViewClient extends WebViewClient {
	private static final String TAG = BakaTsukiWebViewClient.class.toString();
	protected WeakReference<DisplayLightNovelContentActivity> activityRef;
	private boolean hasError = false;
	private boolean scaleChangedRunnablePending = false;

	private boolean isExternalNeedSave = true;
	public String currentUrl = "";

	public BakaTsukiWebViewClient(DisplayLightNovelContentActivity caller) {
		super();
		this.activityRef = new WeakReference<DisplayLightNovelContentActivity>(caller);
	}

	public void setExternalNeedSave(boolean value) {
		synchronized (this) {
			isExternalNeedSave = value;
		}
	}

	@Override
	public boolean shouldOverrideUrlLoading(WebView view, String url) {
		hasError = false;
		final DisplayLightNovelContentActivity caller = activityRef.get();
		if (caller == null)
			return false;

		caller.setLastReadState();
		Log.d(TAG, "Handling: " + url);
		currentUrl = url;

		// if image file
		if (url.contains("title=File:")) {
			Intent intent = new Intent(caller, DisplayImageActivity.class);
			intent.putExtra(Constants.EXTRA_IMAGE_URL, url);
			intent.putExtra("image_list", caller.images);
			caller.startActivity(intent);
		} else {
			// get the title from url
			boolean isInternalPages = false;
			if (url.contains("/project/index.php?title=")) {
				String titles[] = url.split("title=", 2);
				if (titles.length == 2 && !(titles[1].length() == 0)) {
					// check if have inside db
					NovelsDao dao = NovelsDao.getInstance();
					try {
						// split anchor text
						String[] titles2 = titles[1].split("#", 2);

						// check if load different page.
						synchronized (caller.content) {
							String currentPage = caller.content.getPage();
							if (!currentPage.equalsIgnoreCase(titles2[0])) {
								Log.d(TAG, "Got different page name: " + titles2[0]);
								PageModel tempPage = new PageModel();
								tempPage.setPage(titles2[0]);
								PageModel pageModel = dao.getPageModel(tempPage, null, false);
								if (pageModel != null) {
									caller.jumpTo(pageModel);
									Log.d(TAG, "Loading : " + pageModel.getPage());
								}
								else {
									Log.w(TAG, "PageModel not downloaded yet, most likely not listed in chapter list: " + titles2[0]);
									tempPage.setTitle(titles2[0]);
									tempPage.setParent(currentPage);
									tempPage.setType(PageModel.TYPE_CONTENT);
									dao.updatePageModel(tempPage);
									caller.jumpTo(tempPage);
								}
							} else {
								Log.d(TAG, "Already loaded");
							}
							// navigate to the anchor if exist.
							if (titles2.length == 2) {
								view.loadUrl("#" + titles2[1]);
							}
						}

						isInternalPages = true;
					} catch (Exception e) {
						Log.e(TAG, "Failed to load: " + titles[1], e);
					}
				}
			}

			if (!isInternalPages) {
				boolean useInternalWebView = PreferenceManager.getDefaultSharedPreferences(caller).getBoolean(Constants.PREF_USE_INTERNAL_WEBVIEW, false);
				if (useInternalWebView) {

					// check if the same page and trying to load anchor link.
					// need to handle if page use urlparams to do navigations.
					String currUrl = view.getUrl();// caller.getIntent().getStringExtra(Constants.EXTRA_PAGE);
					String[] urlParts = url.split("#", 2);
					String[] urlQuery = urlParts[0].split("\\?", 2);
					if (!Util.isStringNullOrEmpty(currUrl) && currUrl.startsWith(urlQuery[0])) {
						if (urlParts.length == 2)
							view.loadUrl("javascript:window.location.hash=" + urlParts[1] + ";");
					}
					else {
						PageModel pageModel = new PageModel();
						pageModel.setPage(url);
						PageModel temp = pageModel;
						try {
							temp = NovelsDao.getInstance().getExistingPageModel(pageModel, null);
						} catch (Exception e) {
							Log.e(TAG, "Failed to get pageModel: " + url, e);
						}
						if (temp != null)
							pageModel = temp;
						caller.loadExternalUrl(pageModel, false);
					}
				} else {
					// set the intent page to the current page
					caller.getIntent().removeExtra(Constants.EXTRA_PAGE);
					caller.getIntent().putExtra(Constants.EXTRA_PAGE, caller.content.getPage());

					// use default handler.
					caller.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
				}
			}
		}
		return true;
	}

	@Override
	public void onPageFinished(WebView view, String url) {
		super.onPageFinished(view, url);
		final DisplayLightNovelContentActivity caller = activityRef.get();
		if (caller != null && !hasError)
		{
			NonLeakingWebView wv = (NonLeakingWebView) caller.findViewById(R.id.webViewContent);
			String page = caller.getIntent().getStringExtra(Constants.EXTRA_PAGE);

			// assumption all external page is start with http
			// and ignore for internal pages which loaded with base url to bakatsuki.org
			if (url.startsWith("http") && !url.startsWith(UIHelper.getBaseUrl(view.getContext()))) {
				if (!isExternalNeedSave || !getAllowSaveExternal()) {
					Log.d(TAG, "Skip auto save for: " + page + " " + !isExternalNeedSave + " " + !getAllowSaveExternal());
					return;
				}
				wv.saveMyWebArchive(url);
			}
		}
	}

	private boolean getAllowSaveExternal() {
		return PreferenceManager.getDefaultSharedPreferences(LNReaderApplication.getInstance()).getBoolean(Constants.PREF_SAVE_EXTERNAL_URL, true);
	}

	@Override
	public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
		super.onReceivedError(view, errorCode, description, failingUrl);
		hasError = true;
		Log.w(TAG, String.format("Error detected: [%s] %s => %s", errorCode, description, failingUrl));
	}

	/**
	 * KitKat chromium text zoom handler, see http://stackoverflow.com/a/20000193
	 * 
	 * @param webView
	 * @param oldScale
	 * @param newScale
	 */
	@Override
	public void onScaleChanged(final WebView webView, float oldScale, float newScale) {
		if (UIHelper.getKitKatWebViewFix(webView.getContext())) {
			if (scaleChangedRunnablePending) {
				Log.d(TAG, "OnScaleChange KitKat handler already running");
				return;
			}
			synchronized (webView) {
				scaleChangedRunnablePending = true;
				webView.postDelayed(new Runnable() {
					@Override
					public void run() {
						webView.loadUrl("javascript:recalcWidth();", null);
						scaleChangedRunnablePending = false;
					}
				}, UIHelper.getIntFromPreferences(Constants.PREF_KITKAT_WEBVIEW_FIX_DELAY, 500));
			}
		}
	}
}
