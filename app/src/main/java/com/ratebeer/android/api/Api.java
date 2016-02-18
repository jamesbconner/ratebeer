package com.ratebeer.android.api;

import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.pacoworks.rxtuples.RxTuples;
import com.ratebeer.android.BuildConfig;
import com.ratebeer.android.Session;
import com.ratebeer.android.api.model.BeerDetails;
import com.ratebeer.android.api.model.BeerDetailsDeserializer;
import com.ratebeer.android.api.model.BeerRating;
import com.ratebeer.android.api.model.BeerRatingDeserializer;
import com.ratebeer.android.api.model.BeerSearchResult;
import com.ratebeer.android.api.model.BeerSearchResultDeserializer;
import com.ratebeer.android.api.model.FeedItem;
import com.ratebeer.android.api.model.FeedItemDeserializer;
import com.ratebeer.android.api.model.UserInfo;
import com.ratebeer.android.api.model.UserInfoDeserializer;
import com.ratebeer.android.api.model.UserRateCount;
import com.ratebeer.android.api.model.UserRateCountDeserializer;
import com.ratebeer.android.api.model.UserRating;
import com.ratebeer.android.api.model.UserRatingDeserializer;
import com.ratebeer.android.db.RBLog;
import com.ratebeer.android.rx.AsRangeOperator;

import org.javatuples.Pair;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.util.concurrent.TimeUnit;

import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.GsonConverterFactory;
import retrofit2.Retrofit;
import retrofit2.RxJavaCallAdapterFactory;
import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;
import rx.schedulers.Schedulers;

public final class Api {

	private static final String ENDPOINT = "http://www.ratebeer.com/json/";
	private static final String KEY = "tTmwRTWT-W7tpBhtL";
	private static final String COOKIE_USERID = "UserID";
	private static final String COOKIE_SESSIONID = "SessionID";
	private static final int RATINGS_PER_PAGE = 100;

	private final Routes routes;
	private final CookieManager cookieManager;

	private static class Holder {
		// Holder with static instance which implements a thread safe lazy loading singleton
		static final Api INSTANCE = new Api();
	}

	public static Api get() {
		return Holder.INSTANCE;
	}

	public static Api api() {
		return get();
	}

	private Api() {

		// @formatter:off
		HttpLoggingInterceptor logging = new HttpLoggingInterceptor(RBLog::v);
		if (BuildConfig.DEBUG)
			logging.setLevel(HttpLoggingInterceptor.Level.HEADERS);
		cookieManager = new CookieManager(new PersistentCookieStore(), CookiePolicy.ACCEPT_ORIGINAL_SERVER);
		OkHttpClient httpclient = new OkHttpClient.Builder()
				.connectTimeout(5, TimeUnit.SECONDS)
				.writeTimeout(5, TimeUnit.SECONDS)
				.readTimeout(10, TimeUnit.SECONDS)
				.cookieJar(new JavaNetCookieJar(cookieManager))
				.addInterceptor(logging)
				.addNetworkInterceptor(new LoginHeaderInterceptor())
				.build();
		Gson gson = new GsonBuilder()
				.registerTypeAdapter(FeedItem.class, new FeedItemDeserializer())
				.registerTypeAdapter(UserInfo.class, new UserInfoDeserializer())
				.registerTypeAdapter(UserRateCount.class, new UserRateCountDeserializer())
				.registerTypeAdapter(UserRating.class, new UserRatingDeserializer())
				.registerTypeAdapter(BeerSearchResult.class, new BeerSearchResultDeserializer())
				.registerTypeAdapter(BeerDetails.class, new BeerDetailsDeserializer())
				.registerTypeAdapter(BeerRating.class, new BeerRatingDeserializer())
				.create();
		Retrofit retrofit = new Retrofit.Builder()
				.baseUrl(ENDPOINT)
				.client(httpclient)
				.addCallAdapterFactory(RxJavaCallAdapterFactory.create())
				.addConverterFactory(GsonConverterFactory.create(gson))
				.build();
		// @formatter:on
		routes = retrofit.create(Routes.class);

	}

	private boolean haveLoginCookie() {
		if (cookieManager.getCookieStore().getCookies().isEmpty())
			return false;
		boolean hasUserCookie = false, hasSessionCookie = false;
		for (HttpCookie cookie : cookieManager.getCookieStore().getCookies()) {
			if (cookie.getName().equals(COOKIE_USERID) && !TextUtils.isEmpty(cookie.getValue()))
				hasUserCookie = true;
			if (cookie.getName().equals(COOKIE_SESSIONID) && !TextUtils.isEmpty(cookie.getValue()))
				hasSessionCookie = true;
		}
		return hasUserCookie && hasSessionCookie;
	}

	/**
	 * Performs a login call to the server to load a session cookie; this is typically used as someLoginDependendCall.startWith(getLoginCookie())
	 */
	private <T> Observable<T> getLoginCookie() {
		// Execute a login request to make sure we have a login cookie
		return routes.login(Session.get().getUserName(), Session.get().getPassword(), "on").subscribeOn(Schedulers.io())
				.flatMap(result -> Observable.empty());
	}

	public Observable<Boolean> login(String username, String password) {
		// @formatter:off
		return Observable.zip(
					routes.getUserInfo(KEY, username).subscribeOn(Schedulers.newThread()).flatMapIterable(infos -> infos).first(),
					routes.login(Session.get().getUserName(), Session.get().getPassword(), "on").subscribeOn(Schedulers.newThread()),
					(userInfo, foo) -> userInfo)
				// Add to the user id the user's rate counts
				.flatMap(user -> Observable.zip(
						Observable.just(user),
						routes.getUserRateCount(KEY, user.userId).flatMapIterable(userRateCounts -> userRateCounts),
						RxTuples.toPair()))
				// Store in our own instance the new user data
				.doOnNext(user -> Session.get().startSession(user.getValue0().userId, username, password, user.getValue1()))
				// Return login success
				.map(ignore -> true);
		// @formatter:on
	}

	/**
	 * Calls the server to log out, clear cookies and clear our local session
	 */
	public Observable<Boolean> logout() {
		return routes.logout().map(result -> true).map(success -> cookieManager.getCookieStore().removeAll())
				.doOnNext(success -> Session.get().endSession());
	}

	/**
	 * Returns an observable sequence (list) of items that appear on the global news feed; does not require user login
	 */
	public Observable<FeedItem> getGlobalFeed() {
		return routes.getFeed(KEY, 1).flatMapIterable(items -> items);
	}

	/**
	 * Returns an observable sequence (list) of items that appear on the local news feed; requires a user to be logged in for its locale
	 */
	public Observable<FeedItem> getLocalFeed() {
		Observable<FeedItem> feed = routes.getFeed(KEY, 2).flatMapIterable(items -> items);
		if (!haveLoginCookie())
			feed = feed.startWith(getLoginCookie());
		return feed;
	}

	/**
	 * Returns an observable sequence (list) of items that appear on the personalized friends feed; requires a user to be logged in
	 */
	public Observable<FeedItem> getFriendsFeed() {
		Observable<FeedItem> feed = routes.getFeed(KEY, 0).flatMapIterable(items -> items);
		if (!haveLoginCookie())
			feed = feed.startWith(getLoginCookie());
		return feed;
	}

	/**
	 * Returns an observable sequence (list) of beers (search results) for a text query
	 */
	public Observable<BeerSearchResult> searchBeers(String query) {
		return routes.searchBeers(KEY, Session.get().getUserId(), Normalizer.get().normalizeSearchQuery(query)).flatMapIterable(results -> results);
	}

	/**
	 * Returns the full details for a beer, or throws an exception if it could not be retrieved
	 */
	public Observable<BeerDetails> getBeerDetails(long beerId) {
		return routes.getBeerDetails(KEY, (int) beerId).flatMapIterable(beers -> beers).first();
	}

	/**
	 * Returns a (possibly empty) observable sequence (list) of the most recent ratings for a beer
	 */
	public Observable<BeerRating> getBeerRatings(long beerId) {
		return routes.getBeerRatings(KEY, (int) beerId, null, 1, 1).flatMapIterable(ratings -> ratings);
	}

	/**
	 * Returns the beer rating of a specific user, or null if the user did not rate it yet
	 */
	public Observable<BeerRating> getBeerUserRating(long beerId, long userId) {
		return routes.getBeerRatings(KEY, (int) beerId, (int) userId, 1, 1).flatMapIterable(ratings -> ratings).firstOrDefault(null);
	}

	/**
	 * Returns a (possibly empty) observable sequence (list) of all ratings by the logged in user, from most recent to oldest
	 * @param onPageProgress An action, run on the main (UI) thread, which can report sync progress
	 */
	public Observable<UserRating> getUserRatings(Action1<Float> onPageProgress) {
		if (Session.get().getUserId() == null)
			return Observable.empty();
		// Based on the up-to-date rate count, get all pages of ratings necessary and emit them in reverse order
		Observable<Integer> pageCount =
				routes.getUserRateCount(KEY, Session.get().getUserId()).subscribeOn(Schedulers.io()).flatMapIterable(counts -> counts)
						.map(counts -> (int) Math.ceil((float) counts.rateCount / RATINGS_PER_PAGE));
		Observable<UserRating> ratings =
				Observable.combineLatest(pageCount, pageCount.lift(new AsRangeOperator()).onBackpressureBuffer(), RxTuples.toPair()).flatMap(
						page -> Observable.combineLatest(Observable.just(page), routes.getUserRatings(KEY, page.getValue1() + 1), RxTuples.toPair()))
						.observeOn(AndroidSchedulers.mainThread()).doOnNext(
						objects -> onPageProgress.call((((float) objects.getValue0().getValue1() + 1) / objects.getValue0().getValue0()) * 100))
						.observeOn(Schedulers.io()).flatMapIterable(Pair::getValue1);
		if (!haveLoginCookie())
			ratings = ratings.startWith(getLoginCookie());
		return ratings;
	}

}
