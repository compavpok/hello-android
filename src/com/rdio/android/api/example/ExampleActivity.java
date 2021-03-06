/*
 Copyright (c) 2011 Rdio Inc
 
 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:
 
 The above copyright notice and this permission notice shall be included in
 all copies or substantial portions of the Software.
 
 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 THE SOFTWARE.
 */

package com.rdio.android.api.example;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONObject;

import com.rdio.android.api.Rdio;
import com.rdio.android.api.RdioApiCallback;
import com.rdio.android.api.RdioListener;
import com.rdio.android.api.services.RdioAuthorisationException;
import com.rdio.android.api.RdioSubscriptionType;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageView;
import android.widget.Toast;

/**
 * Really basic test app for the Rdio playback API.
 */
public class ExampleActivity extends Activity implements RdioListener {
	private static final String TAG = "RdioAPIExample";

	private MediaPlayer player;

	private Queue<Track> trackQueue;

	private static Rdio rdio;

	// TODO CHANGE THIS TO YOUR APPLICATION KEY AND SECRET
	//private static final String appKey = "PutYourKeyHere";
	//private static final String appSecret = "PutYourSecretHere";

	private static String accessToken = null;
	private static String accessTokenSecret = null;

	// Request code used for startActivityForResult/onActivityResult
	private static final int REQUEST_AUTHORISE_APP = 100;

	// Dialog codes used for createDialog
	private static final int DIALOG_GETTING_USER = 100;
	private static final int DIALOG_GETTING_COLLECTION = 101;
	private static final int DIALOG_GETTING_HEAVY_ROTATION = 102;

	private static final String PREF_ACCESSTOKEN = "prefs.accesstoken";
	private static final String PREF_ACCESSTOKENSECRET = "prefs.accesstokensecret";

	private static String collectionKey = null;

	private ImageView albumArt;
	private ImageView playPause;

	// Our model for the metadata for a track that we care about
	private class Track {
		public String key;
		public String trackName;
		public String artistName;
		public String albumName;
		public String albumArt;

		public Track(String k, String name, String artist, String album, String uri) {
			key = k;
			trackName = name;
			artistName = artist;
			albumName = album;
			albumArt = uri;
		}
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		trackQueue = new LinkedList<Track>();

		// Initialise our Rdio object.  If we have cached access credentials, then use them - otherwise
		// initialise w/ null values and the user will be prompted (if the Rdio app is installed), or
		// we'll fallback to 30s samples.
		if (rdio == null) {
			SharedPreferences settings = getPreferences(MODE_PRIVATE);
			accessToken = settings.getString(PREF_ACCESSTOKEN, null);
			accessTokenSecret = settings.getString(PREF_ACCESSTOKENSECRET, null);

			if (accessToken == null || accessTokenSecret == null) {
				// If either one is null, reset both of them
				accessToken = accessTokenSecret = null;
			} else {
				Log.d(TAG, "Found cached credentials:");
				Log.d(TAG, "Access token: " + accessToken);
				Log.d(TAG, "Access token secret: " + accessTokenSecret);
			}

			// Initialise our API object
			rdio = new Rdio(appKey, appSecret, accessToken, accessTokenSecret, this, this);	
		}

		ImageView i = (ImageView)findViewById(R.id.next);
		i.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				next(true);
			}
		});

		playPause = (ImageView)findViewById(R.id.playPause);
		playPause.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				playPause();
			}
		});

		albumArt = (ImageView)findViewById(R.id.albumArt);
	}

	@Override
	public void onDestroy() {
		Log.i(TAG, "Cleaning up..");

		// Make sure to call the cleanup method on the API object
		rdio.cleanup();

		// If we allocated a player, then cleanup after it
		if (player != null) {
			player.reset();
			player.release();
			player = null;
		}

		super.onDestroy();
	}

	/**
	 * Get Rdio's site-wide heavy rotation and play 30s samples.
	 * Doesn't require auth or the Rdio app to be installed
	 */
	private void doSomethingWithoutApp() {
		Log.i(TAG, "Getting heavy rotation");

		showDialog(DIALOG_GETTING_HEAVY_ROTATION);

		List<NameValuePair> args = new LinkedList<NameValuePair>();
		args.add(new BasicNameValuePair("type", "albums"));
		rdio.apiCall("getHeavyRotation", args, new RdioApiCallback() {
			@Override
			public void onApiSuccess(JSONObject result) {
				try {
					//Log.i(TAG, "Heavy rotation: " + result.toString(2));
					JSONArray albums = result.getJSONArray("result");
					final ArrayList<String> albumKeys = new ArrayList<String>(albums.length());
					for (int i=0; i<albums.length(); i++) {
						JSONObject album = albums.getJSONObject(i);
						String albumKey = album.getString("key");
						albumKeys.add(albumKey);
					}

					// Build our argument to pass to the get api
					StringBuffer keyBuffer = new StringBuffer();
					Iterator<String> iter = albumKeys.iterator();
					while (iter.hasNext()) {
						keyBuffer.append(iter.next());
						if (iter.hasNext()) {
							keyBuffer.append(",");
						}
					}
					Log.i(TAG, "album keys to fetch: " + keyBuffer.toString());
					List<NameValuePair> getArgs = new LinkedList<NameValuePair>();
					getArgs.add(new BasicNameValuePair("keys", keyBuffer.toString()));
					getArgs.add(new BasicNameValuePair("extras", "tracks"));

					// Get more details (like tracks) for all the albums we parsed out of the heavy rotation
					rdio.apiCall("get", getArgs, new RdioApiCallback() {
						@Override
						public void onApiFailure(String methodName, Exception e) {
							Log.e(TAG, "get() failed!", e);
						}

						@Override
						public void onApiSuccess(JSONObject result) {
							try {
								//Log.i(TAG, "get result: " + result.toString(2));
								result = result.getJSONObject("result");
								List<Track> trackKeys = new LinkedList<Track>();

								// Build our list of tracks to put into the player queue
								for (String albumKey : albumKeys) {
									if (!result.has(albumKey)) {
										Log.w(TAG, "result didn't contain album key: " + albumKey);
										continue;
									}
									JSONObject album = result.getJSONObject(albumKey);
									JSONArray tracks = album.getJSONArray("tracks");
									Log.i(TAG, "album " + albumKey + " has " + tracks.length() + " tracks");
									for (int i=0; i<tracks.length(); i++) {
										JSONObject trackObject = tracks.getJSONObject(i);
										String key = trackObject.getString("key");
										String name = trackObject.getString("name");
										String artist = trackObject.getString("artist");
										String albumName = trackObject.getString("album");
										String albumArt = trackObject.getString("icon");
										Log.d(TAG, "Found track: " + key + " => " + trackObject.getString("name"));
										trackKeys.add(new Track(key, name, artist, albumName, albumArt));
									}
								}
								if (trackKeys.size() > 1)
									trackQueue.addAll(trackKeys);
								dismissDialog(DIALOG_GETTING_HEAVY_ROTATION);

								// If we're not playing something, then load something up
								if (player == null || !player.isPlaying())
									next(true);
							} catch (Exception e) {
								Log.e(TAG, "Failed to handle JSONObject: ", e);
							}
						}
					});
				} catch (Exception e) {
					Log.e(TAG, "Failed to handle JSONObject: ", e);
				} finally {
					dismissDialog(DIALOG_GETTING_HEAVY_ROTATION);
				}
			}

			@Override
			public void onApiFailure(String methodName, Exception e) {
				dismissDialog(DIALOG_GETTING_HEAVY_ROTATION);
				Log.e(TAG, "getHeavyRotation failed. ", e);
			}
		});
	}

	/**
	 * Get the current user, and load their collection to start playback with.
	 * Requires authorisation and the Rdio app to be installed.
	 */
	private void doSomething() {
		if (rdio.getSubscriptionState() == RdioSubscriptionType.ANONYMOUS) {
			doSomethingWithoutApp();
			return;
		}

		Log.i(TAG, "Getting current user");
		showDialog(DIALOG_GETTING_USER);

		// Get the current user so we can find out their user ID and get their collection key
		List<NameValuePair> args = new LinkedList<NameValuePair>();
		args.add(new BasicNameValuePair("extras", "followingCount,followerCount,username,displayName,subscriptionType,trialEndDate,actualSubscriptionType"));
		rdio.apiCall("currentUser", args, new RdioApiCallback() {
			@Override
			public void onApiSuccess(JSONObject result) {
				dismissDialog(DIALOG_GETTING_USER);
				try {
					result = result.getJSONObject("result");
					Log.i(TAG, result.toString(2));

					// c<userid> is the 'collection radio source' key
					collectionKey = result.getString("key").replace('s','c');

					LoadMoreTracks();
				} catch (Exception e) {
					Log.e(TAG, "Failed to handle JSONObject: ", e);
				}
			}
			@Override
			public void onApiFailure(String methodName, Exception e) {
				dismissDialog(DIALOG_GETTING_USER);
				Log.e(TAG, "getCurrentUser failed. ", e);
				if (e instanceof RdioAuthorisationException) {
					doSomethingWithoutApp();
				}
			}
		});
	}

	private void LoadMoreTracks() {
		if (rdio.getSubscriptionState() == RdioSubscriptionType.ANONYMOUS) {
			Log.i(TAG, "Anonymous user! No more tracks to play.");

			// Notify the user we're out of tracks
			Toast.makeText(this, getString(R.string.no_more_tracks), Toast.LENGTH_LONG).show();

			// Then helpfully point them to the market to go install Rdio ;)
			Intent installRdioIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=pname:com.rdio.android.ui")); 
			installRdioIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(installRdioIntent);

			finish();
			return;
		}

		showDialog(DIALOG_GETTING_COLLECTION);
		List<NameValuePair> args = new LinkedList<NameValuePair>();
		args.add(new BasicNameValuePair("keys", collectionKey));
		args.add(new BasicNameValuePair("count", "50"));
		rdio.apiCall("get", args, new RdioApiCallback() {
			@Override
			public void onApiFailure(String methodName, Exception e) {
				dismissDialog(DIALOG_GETTING_COLLECTION);
				Log.e(TAG, methodName + " failed: ", e);
			}

			@Override
			public void onApiSuccess(JSONObject result) {
				try {
					result = result.getJSONObject("result");
					result = result.getJSONObject(collectionKey);
					List<Track> trackKeys = new LinkedList<Track>();
					JSONArray tracks = result.getJSONArray("tracks");
					for (int i=0; i<tracks.length(); i++) {
						JSONObject trackObject = tracks.getJSONObject(i);
						String key = trackObject.getString("key");
						String name = trackObject.getString("name");
						String artist = trackObject.getString("artist");
						String album = trackObject.getString("album");
						String albumArt = trackObject.getString("icon");
						Log.d(TAG, "Found track: " + key + " => " + trackObject.getString("name"));
						trackKeys.add(new Track(key, name, artist, album, albumArt));
					}
					if (trackKeys.size() > 1)
						trackQueue.addAll(trackKeys);
					dismissDialog(DIALOG_GETTING_COLLECTION);

					// If we're not playing something, then load something up
					if (player == null || !player.isPlaying())
						next(true);

				} catch (Exception e) {
					dismissDialog(DIALOG_GETTING_COLLECTION);
					Log.e(TAG, "Failed to handle JSONObject: ", e);
				}
			}    		
		});
	}

	private void next(final boolean manualPlay) {
		if (player != null) {
			player.stop();
			player.release();
			player = null;
		}

		final Track track = trackQueue.poll();
		if (trackQueue.size() < 3) {
			Log.i(TAG, "Track queue depleted, loading more tracks");
			LoadMoreTracks();
		}

		if (track == null) {
			Log.e(TAG, "Track is null!  Size of queue: " + trackQueue.size());
			return;
		}

		// Load the next track in the background and prep the player (to start buffering)
		// Do this in a bkg thread so it doesn't block the main thread in .prepare()
		AsyncTask<Track, Void, Track> task = new AsyncTask<Track, Void, Track>() {
			@Override
			protected Track doInBackground(Track... params) {
				Track track = params[0];
				try {
					player = rdio.getPlayerForTrack(track.key, null, manualPlay);
					player.prepare();
					player.setOnCompletionListener(new OnCompletionListener() {
						@Override
						public void onCompletion(MediaPlayer mp) {
							next(false);
						}
					});
					player.start();
				} catch (Exception e) {
					Log.e("Test", "Exception " + e);
				}
				return track;
			}

			@Override
			protected void onPostExecute(Track track) {
				updatePlayPause(true);
			}
		};
		task.execute(track);

		// Fetch album art in the background and then update the UI on the main thread
		AsyncTask<Track, Void, Bitmap> artworkTask = new AsyncTask<Track, Void, Bitmap>() {
			@Override
			protected Bitmap doInBackground(Track... params) {
				Track track = params[0];
				try {
					String artworkUrl = track.albumArt.replace("square-200", "square-600");
					Log.i(TAG, "Downloading album art: " + artworkUrl);
					Bitmap bm = null; 
					try { 
						URL aURL = new URL(artworkUrl); 
						URLConnection conn = aURL.openConnection(); 
						conn.connect(); 
						InputStream is = conn.getInputStream(); 
						BufferedInputStream bis = new BufferedInputStream(is); 
						bm = BitmapFactory.decodeStream(bis); 
						bis.close(); 
						is.close(); 
					} catch (IOException e) { 
						Log.e(TAG, "Error getting bitmap", e); 
					} 
					return bm; 				
				} catch (Exception e) {
					Log.e(TAG, "Error downloading artwork", e);
					return null;
				}
			}

			@Override
			protected void onPostExecute(Bitmap artwork) {
				if (artwork != null) {
					albumArt.setImageBitmap(artwork);
				} else
					albumArt.setImageResource(R.drawable.blank_album_art);
			}
		};
		artworkTask.execute(track);

		Toast.makeText(this, String.format(getResources().getString(R.string.now_playing), track.trackName, track.albumName, track.artistName), Toast.LENGTH_LONG).show();
	}

	private void playPause() {
		if (player != null) {
			if (player.isPlaying()) {
				player.pause();
				updatePlayPause(false);
			} else {
				player.start();
				updatePlayPause(true);
			}
		} else {
			next(true);
		}
	}

	private void updatePlayPause(boolean playing) {
		if (playing) {
			playPause.setImageResource(R.drawable.pause);
		} else {
			playPause.setImageResource(R.drawable.play);
		}
	}

	/*************************
	 * RdioListener Interface
	 *************************/

	/*
	 * Dispatched by the Rdio object when the Rdio object is done initialising, and a connection
	 * to the Rdio app service has been established.  If authorised is true, then we reused our
	 * existing OAuth credentials, and the API is ready for use.
	 * @see com.rdio.android.api.RdioListener#onRdioReady()
	 */
	@Override
	public void onRdioReady() {
		Log.i(TAG, "User state is " + rdio.getSubscriptionState() + " fullstream " + rdio.canUserPlayFullStreams());
		doSomething();
	}

	@Override
	public void onRdioUserPlayingElsewhere() {
		Log.w(TAG, "Tell the user tha playback is stopping.");
	}

	/*
	 * Dispatched by the Rdio object when app approval is needed.  Take the authorisation intent given
	 * and invoke the activity for it
	 * @see com.rdio.android.api.RdioListener#onRdioUserAppApprovalNeeded(android.content.Intent)
	 */
	@Override
	public void onRdioUserAppApprovalNeeded(Intent authorisationIntent) {
		try {
			startActivityForResult(authorisationIntent, REQUEST_AUTHORISE_APP);
		} catch (ActivityNotFoundException e) {
			// Rdio app not found
			Log.e(TAG, "Rdio app not found, limited to 30s samples.");
		}
	}

	/*
	 * Dispatched by the Rdio object once the setTokenAndSecret call has finished, and the credentials are
	 * ready to be used to make API calls.  The token & token secret are passed in so that you can
	 * save/cache them for future re-use.
	 * @see com.rdio.android.api.RdioListener#onRdioAuthorised(java.lang.String, java.lang.String)
	 */
	@Override
	public void onRdioAuthorised(String accessToken, String accessTokenSecret) {
		Log.i(TAG, "Application authorised, saving access token & secret.");
		Log.d(TAG, "Access token: " + accessToken);
		Log.d(TAG, "Access token secret: " + accessTokenSecret);

		SharedPreferences settings = getPreferences(MODE_PRIVATE);
		Editor editor = settings.edit();
		editor.putString(PREF_ACCESSTOKEN, accessToken);
		editor.putString(PREF_ACCESSTOKENSECRET, accessTokenSecret);
		editor.commit();

		doSomething();
	}

	/*************************
	 * Activity overrides
	 *************************/	
	@Override
	protected Dialog onCreateDialog(int id) {
		switch (id) {
		case DIALOG_GETTING_USER:
			return ProgressDialog.show(this, "", getResources().getString(R.string.getting_user));
		case DIALOG_GETTING_COLLECTION:
			return ProgressDialog.show(this, "", getResources().getString(R.string.getting_collection));
		case DIALOG_GETTING_HEAVY_ROTATION:
			return ProgressDialog.show(this, "", getResources().getString(R.string.getting_heavy_rotation));
		}
		return null;
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case REQUEST_AUTHORISE_APP:
			if (resultCode == Rdio.RESULT_AUTHORISATION_ACCEPTED) {
				Log.i(TAG, "User authorised our app.");
				rdio.setTokenAndSecret(data);					
			} else if (resultCode == Rdio.RESULT_AUTHORISATION_REJECTED) {
				Log.i(TAG, "User rejected our app.");
			}
			break;
		default:
			break;
		}
	}
}